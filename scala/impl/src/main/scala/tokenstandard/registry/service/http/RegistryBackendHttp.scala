package tokenstandard.registry.service.http

import cats.effect.Concurrent
import cats.syntax.all.*
import com.daml.ledger.javaapi.data.DisclosedContract
import com.daml.ledger.javaapi.data.Identifier
import com.google.protobuf.ByteString
import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationinstructionv2.AllocationInstruction
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch
import daml.splice.api.token.metadatav1.ChoiceContext
import daml.splice.api.token.metadatav1.ExtraArgs
import daml.splice.api.token.metadatav1.Metadata
import daml.splice.api.token.transferinstructionv2.TransferFactory_Transfer
import daml.splice.api.token.transferinstructionv2.TransferInstruction
import io.circe.Json
import io.circe.parser
import org.http4s.Method
import org.http4s.Request
import org.http4s.Status
import org.http4s.Uri
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import tokenstandard.registry.RegistryApi
import tokenstandard.registry.RegistryApi.EnrichedFactoryChoice
import tokenstandard.registry.RegistryApi.OpenApiChoiceContext
import tokenstandard.registry.openapi.alloc.models as al
import tokenstandard.registry.openapi.allocinstr.models as ai
import tokenstandard.registry.openapi.metadata.models as md

import java.util.Base64
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** HTTP client to a remote CIP-0112 registry — the consumer counterpart of [[RegistryRoutes]] and
  * an alternative [[RegistryApi]] implementation (a wallet/app calls this to obtain the context +
  * disclosures before exercising a factory choice). Each factory call encodes the choice argument
  * as Daml-JSON (`choiceArguments`, via the codegen `jsonEncoder`), POSTs it, and rebuilds an
  * [[EnrichedFactoryChoice]] from the returned factory id + `ChoiceContext` + disclosures — the
  * inverse of what the local reference [[LocalRegistryApi]] assembles. Each lifecycle handler POSTs
  * the contract id (in the path) and rebuilds an [[OpenApiChoiceContext]] the same way.
  *
  * `client` is the http4s `Client[F]` abstraction, so the caller injects the concrete backend
  * (ember in prod, `Client.fromHttpApp(routes)` in tests).
  *
  * Only two OpenAPI specs are codegen'd (`ai` = allocation-instruction, `al` = allocation), giving
  * identical DTO shapes in two packages; there is no transfer-instruction DTO package, so the
  * transfer endpoints reuse `ai`.
  */
final class RegistryBackendHttp[F[_]: Concurrent](
    client: Client[F],
    baseUri: Uri,
) extends RegistryApi[F]:

    // -- Factories ---------------------------------------------------------------------------------

    override def getTransferFactory(
        arg: TransferFactory_Transfer
    ): F[EnrichedFactoryChoice[TransferFactory_Transfer]] =
        val uri = baseUri / "registry" / "transfer-instruction" / "v2" / "transfer-factory"
        val body = ai.GetFactoryRequest(damlJson(arg.jsonEncoder().intoString()), None)
        client
            .expect[ai.FactoryWithChoiceContext](Request[F](Method.POST, uri).withEntity(body))
            .flatMap { r =>
                enriched(
                  r.factoryId,
                  r.choiceContext.choiceContextData,
                  disclosures(r.choiceContext)
                ) { ctx =>
                    new TransferFactory_Transfer(
                      arg.transfer,
                      arg.actors,
                      new ExtraArgs(ctx, arg.extraArgs.meta),
                    )
                }
            }

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

    // -- Allocation lifecycle choice contexts ------------------------------------------------------

    override def getAllocationWithdrawContext(
        allocation: Allocation.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        contextCall(
          baseUri / "registry" / "allocations" / "v2" / allocation.contractId /
              "choice-contexts" / "withdraw",
          meta,
        )

    override def getAllocationCancelContext(
        allocation: Allocation.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        contextCall(
          baseUri / "registry" / "allocations" / "v2" / allocation.contractId /
              "choice-contexts" / "cancel",
          meta,
        )

    // -- Allocation-instruction choice contexts ----------------------------------------------------

    override def getAllocationInstructionWithdrawContext(
        instruction: AllocationInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        contextCall(
          baseUri / "registry" / "allocation-instruction" / "v2" / instruction.contractId /
              "choice-contexts" / "withdraw",
          meta,
        )

    override def getAllocationInstructionAcceptContext(
        instruction: AllocationInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        contextCall(
          baseUri / "registry" / "allocation-instruction" / "v2" / instruction.contractId /
              "choice-contexts" / "accept",
          meta,
        )

    // -- Transfer-instruction choice contexts ------------------------------------------------------

    override def getTransferInstructionAcceptContext(
        instruction: TransferInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        contextCall(
          baseUri / "registry" / "transfer-instruction" / "v2" / instruction.contractId /
              "choice-contexts" / "accept",
          meta,
        )

    override def getTransferInstructionRejectContext(
        instruction: TransferInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        contextCall(
          baseUri / "registry" / "transfer-instruction" / "v2" / instruction.contractId /
              "choice-contexts" / "reject",
          meta,
        )

    override def getTransferInstructionWithdrawContext(
        instruction: TransferInstruction.ContractId,
        meta: Metadata,
    ): F[OpenApiChoiceContext] =
        contextCall(
          baseUri / "registry" / "transfer-instruction" / "v2" / instruction.contractId /
              "choice-contexts" / "withdraw",
          meta,
        )

    // -- Registry metadata -------------------------------------------------------------------------

    override def getRegistryInfo: F[md.GetRegistryInfoResponse] =
        client.expect[md.GetRegistryInfoResponse](
          baseUri / "registry" / "metadata" / "v1" / "info"
        )

    override def listInstruments(
        pageSize: Option[Int],
        pageToken: Option[String],
    ): F[md.ListInstrumentsResponse] =
        client.expect[md.ListInstrumentsResponse](
          (baseUri / "registry" / "metadata" / "v1" / "instruments")
              .withOptionQueryParam("pageSize", pageSize)
              .withOptionQueryParam("pageToken", pageToken)
        )

    override def getInstrument(instrumentId: String): F[md.Instrument] =
        val uri = baseUri / "registry" / "metadata" / "v1" / "instruments" / instrumentId
        client.run(Request[F](Method.GET, uri)).use { resp =>
            if resp.status == Status.NotFound then
                Concurrent[F].raiseError(RegistryApi.Error.InstrumentNotFound(instrumentId))
            else if resp.status.isSuccess then resp.as[md.Instrument]
            else
                resp.as[String].flatMap { body =>
                    Concurrent[F].raiseError(RegistryApi.Error.Http(resp.status.code, body))
                }
        }

    // -- response -> EnrichedFactoryChoice / OpenApiChoiceContext -----------------------------------

    /** POST an empty-ish `GetChoiceContextRequest` (the cid is in the path; the service ignores
      * `meta`, but we forward it for spec-completeness) and rebuild the [[OpenApiChoiceContext]]:
      * decode the `choiceContextData` with the codegen decoder and render each disclosed contract.
      */
    private def contextCall(uri: Uri, meta: Metadata): F[OpenApiChoiceContext] =
        val metaMap = meta.values.asScala.toMap
        val body = ai.GetChoiceContextRequest(Option.when(metaMap.nonEmpty)(metaMap), None)
        client
            .expect[ai.ChoiceContext](Request[F](Method.POST, uri).withEntity(body))
            .flatMap { ctx =>
                Concurrent[F].fromEither(Try {
                    OpenApiChoiceContext(
                      ChoiceContext.fromJson(ctx.choiceContextData.noSpaces),
                      toDisclosedContracts(disclosures(ctx)),
                    )
                }.toEither)
            }

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
            EnrichedFactoryChoice(factoryId, rebuild(ctx), toDisclosedContracts(discs))
        }.toEither)

    private def toDisclosedContracts(
        discs: List[(String, String, String, String)]
    ): List[DisclosedContract] =
        discs.map { case (templateId, contractId, blob, synchronizerId) =>
            new DisclosedContract(
              parseIdentifier(templateId),
              contractId,
              ByteString.copyFrom(Base64.getDecoder.decode(blob)),
              synchronizerId,
            )
        }

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
