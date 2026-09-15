package treasury.registry.service.http

import java.util.Base64
import scala.util.Try

import cats.effect.Concurrent
import cats.syntax.all.*

import com.google.protobuf.ByteString
import io.circe.{Json, parser}

import org.http4s.{Method, Request, Uri}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client

import treasury.registry.{RegistryApi, RegistryApiEvent, Tracer}
import treasury.registry.RegistryApi.{Disclosure, EnrichedFactoryChoice}

import treasury.registry.openapi.allocinstr.models as ai
import treasury.registry.openapi.alloc.models as al

import com.daml.ledger.javaapi.data.{DisclosedContract, Identifier}
import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch
import daml.splice.api.token.metadatav1.{ChoiceContext, ExtraArgs}

/** HTTP client to a remote CIP-0112 registry — the consumer counterpart of [[RegistryRoutes]] and
  * an alternative [[RegistryApi]] implementation (a wallet/app calls this to obtain the context +
  * disclosures before exercising a factory choice). Each call encodes the choice argument as
  * Daml-JSON (`choiceArguments`, via the codegen `jsonEncoder`), POSTs it, and rebuilds an
  * [[EnrichedFactoryChoice]] from the returned factory id + `ChoiceContext` + disclosures — the
  * inverse of what the local reference [[LocalRegistryApi]] assembles.
  *
  * `client` is the http4s `Client[F]` abstraction, so the caller injects the concrete backend
  * (ember in prod, `Client.fromHttpApp(routes)` in tests). Only the two factory endpoints
  * [[RegistryRoutes]] serves are implemented; the lifecycle contexts inherit the `notImplemented`
  * default until served.
  */
final class RegistryBackendHttp[F[_]: Concurrent](
    client: Client[F],
    baseUri: Uri,
    protected val tracer: Tracer[F, RegistryApiEvent],
) extends RegistryApi[F]:

    protected def notImplemented[A](endpoint: String): F[A] =
        Concurrent[F].raiseError(RegistryApi.Error.NotImplemented(endpoint))

    override def getAllocationFactory(
        arg: AllocationFactory_Allocate
    ): F[EnrichedFactoryChoice[AllocationFactory_Allocate]] =
        val uri = baseUri / "registry" / "allocation-instruction" / "v2" / "allocation-factory"
        val body = ai.GetFactoryRequest(damlJson(arg.jsonEncoder().intoString()), None)
        client
            .expect[ai.FactoryWithChoiceContext](Request[F](Method.POST, uri).withEntity(body))
            .flatMap { r =>
                enriched(
                  r.factoryId,
                  r.choiceContext.choiceContextData,
                  disclosures(r.choiceContext)
                ) { ctx =>
                    new AllocationFactory_Allocate(
                      arg.settlement,
                      arg.allocation,
                      arg.requestedAt,
                      arg.inputHoldingCids,
                      new ExtraArgs(ctx, arg.extraArgs.meta),
                      arg.actors,
                    )
                }
            }

    override def getSettlementFactory(
        arg: SettlementFactory_SettleBatch
    ): F[EnrichedFactoryChoice[SettlementFactory_SettleBatch]] =
        val uri = baseUri / "registry" / "allocation" / "v2" / "settlement-factory"
        val body = al.GetFactoryRequest(damlJson(arg.jsonEncoder().intoString()), None)
        client
            .expect[al.FactoryWithChoiceContext](Request[F](Method.POST, uri).withEntity(body))
            .flatMap { r =>
                enriched(
                  r.factoryId,
                  r.choiceContext.choiceContextData,
                  disclosures(r.choiceContext)
                ) { ctx =>
                    new SettlementFactory_SettleBatch(
                      arg.settlement,
                      arg.transferLegs,
                      arg.allocations,
                      arg.actors,
                      new ExtraArgs(ctx, arg.extraArgs.meta),
                    )
                }
            }

    // -- response -> EnrichedFactoryChoice ---------------------------------------------------------

    /** Rebuild an [[EnrichedFactoryChoice]] from the wire response: decode the `choiceContextData`
      * (Daml-JSON of a `MetadataV1.ChoiceContext`) with the codegen decoder, render each disclosed
      * contract as a wire `DisclosedContract`, and let `rebuild` embed the context into the arg.
      */
    private def enriched[A](
        factoryId: String,
        choiceContextData: Json,
        discs: List[(String, String, String, String)],
    )(rebuild: ChoiceContext => A): F[EnrichedFactoryChoice[A]] =
        Concurrent[F].fromEither(Try {
            val ctx = ChoiceContext.fromJson(choiceContextData.noSpaces)
            val ds: List[Disclosure] =
                discs.map { case (templateId, contractId, blob, synchronizerId) =>
                    new DisclosedContract(
                      parseIdentifier(templateId),
                      contractId,
                      ByteString.copyFrom(Base64.getDecoder.decode(blob)),
                      synchronizerId,
                    )
                }
            EnrichedFactoryChoice(factoryId, rebuild(ctx), ds)
        }.toEither)

    /** Parse the codegen `jsonEncoder().intoString()` output into circe JSON for the request body.
      */
    private def damlJson(s: String): Json =
        parser
            .parse(s)
            .fold(
              e => throw RegistryApi.Error.Decode(s"choiceArguments JSON: ${e.message}"),
              identity
            )

    private def disclosures(ctx: ai.ChoiceContext): List[(String, String, String, String)] =
        ctx.disclosedContracts.toList.map(d =>
            (d.templateId, d.contractId, d.createdEventBlob, d.synchronizerId)
        )

    private def disclosures(ctx: al.ChoiceContext): List[(String, String, String, String)] =
        ctx.disclosedContracts.toList.map(d =>
            (d.templateId, d.contractId, d.createdEventBlob, d.synchronizerId)
        )

    /** Parse a `<pkgId>:<Module>:<Entity>` template id back into a Ledger-API `Identifier`. */
    private def parseIdentifier(s: String): Identifier =
        s.split(":") match
            case Array(pkg, module, entity) => new Identifier(pkg, module, entity)
            case _ => throw RegistryApi.Error.Decode(s"malformed template id: $s")
