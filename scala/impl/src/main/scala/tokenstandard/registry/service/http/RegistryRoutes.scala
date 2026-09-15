package tokenstandard.registry.service.http

import cats.effect.Concurrent
import cats.syntax.all.*
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import tokenstandard.registry.openapi.alloc.models as al
import tokenstandard.registry.openapi.allocinstr.models as ai
import tokenstandard.registry.openapi.metadata.models as md
import tokenstandard.registry.service.*

/** http4s routes serving the CIP-0112 registry endpoints, backed by a [[RegistryService]] (ledger
  * reads + context assembly) and a [[RegistryMetadata]] (the static catalog):
  *   - the three factories (transfer / allocation / settlement),
  *   - the seven allocation- / transfer-instruction lifecycle choice contexts, and
  *   - the three metadata-v1 endpoints (registry info, instrument list/lookup).
  *
  * The response DTOs are generated per-spec (identical shapes in distinct packages: `ai` =
  * allocation-instruction, `al` = allocation, `md` = metadata); there is no transfer-instruction
  * spec, so the transfer routes reuse `ai`. Each factory route maps [[ContextBundle]] into a
  * `FactoryWithChoiceContext`; each lifecycle route maps it into a `ChoiceContext` (no factory id).
  */
final class RegistryRoutes[F[_]: Concurrent](svc: RegistryService[F], metadata: RegistryMetadata)
    extends Http4sDsl[F]:

    private object PageSize extends OptionalQueryParamDecoderMatcher[Int]("pageSize")
    private object PageToken extends OptionalQueryParamDecoderMatcher[String]("pageToken")

    val routes: HttpRoutes[F] = HttpRoutes.of[F] {
        // -- Registry metadata ---------------------------------------------------------------------
        case GET -> Root / "registry" / "metadata" / "v1" / "info" =>
            Ok(metadata.info)

        case GET -> Root / "registry" / "metadata" / "v1" / "instruments"
            :? PageSize(pageSize) +& PageToken(pageToken) =>
            Ok(metadata.page(pageSize, pageToken))

        case GET -> Root / "registry" / "metadata" / "v1" / "instruments" / instrumentId =>
            metadata
                .instrument(instrumentId)
                .fold(NotFound(md.ErrorResponse(s"instrument not found: $instrumentId")))(Ok(_))

        // -- Factories -----------------------------------------------------------------------------
        case req @ POST -> Root / "registry" / "transfer-instruction" / "v2" / "transfer-factory" =>
            for
                body <- req.as[ai.GetFactoryRequest]
                accounts <- Concurrent[F].fromEither(
                  DamlJson.transferAccounts(body.choiceArguments)
                )
                bundle <- svc.choiceContext(accounts)
                resp <- Ok(toAiFactory(bundle))
            yield resp

        case req @ POST -> Root / "registry" / "allocation-instruction" / "v2" / "allocation-factory" =>
            for
                body <- req.as[ai.GetFactoryRequest]
                authorizer <- Concurrent[F].fromEither(
                  DamlJson.allocationAuthorizer(body.choiceArguments)
                )
                bundle <- svc.getAllocationFactory(authorizer)
                resp <- Ok(toAiFactory(bundle))
            yield resp

        case req @ POST -> Root / "registry" / "allocation" / "v2" / "settlement-factory" =>
            for
                body <- req.as[al.GetFactoryRequest]
                accounts <- Concurrent[F].fromEither(
                  DamlJson.settlementAccounts(body.choiceArguments)
                )
                cids <- Concurrent[F].fromEither(
                  DamlJson.settlementAllocationCids(body.choiceArguments)
                )
                bundle <- svc.getSettlementFactory(accounts, cids)
                resp <- Ok(toAlFactory(bundle))
            yield resp

        // -- Allocation lifecycle contexts (cid in the path; body ignored) -------------------------
        case POST -> Root / "registry" / "allocations" / "v2" / allocationId /
            "choice-contexts" / "withdraw" =>
            svc.allocationContext(Cid(allocationId), includeLocked = false)
                .flatMap(b => Ok(toContext(b)))

        case POST -> Root / "registry" / "allocations" / "v2" / allocationId /
            "choice-contexts" / "cancel" =>
            svc.allocationContext(Cid(allocationId), includeLocked = true)
                .flatMap(b => Ok(toContext(b)))

        // -- Allocation-instruction lifecycle contexts ---------------------------------------------
        case POST -> Root / "registry" / "allocation-instruction" / "v2" / allocationInstructionId /
            "choice-contexts" / "withdraw" =>
            svc.allocationInstructionContext(Cid(allocationInstructionId))
                .flatMap(b => Ok(toContext(b)))

        case POST -> Root / "registry" / "allocation-instruction" / "v2" / allocationInstructionId /
            "choice-contexts" / "accept" =>
            svc.allocationInstructionContext(Cid(allocationInstructionId))
                .flatMap(b => Ok(toContext(b)))

        // -- Transfer-instruction lifecycle contexts -----------------------------------------------
        case POST -> Root / "registry" / "transfer-instruction" / "v2" / transferInstructionId /
            "choice-contexts" / "accept" =>
            svc.transferInstructionContext(Cid(transferInstructionId))
                .flatMap(b => Ok(toContext(b)))

        case POST -> Root / "registry" / "transfer-instruction" / "v2" / transferInstructionId /
            "choice-contexts" / "reject" =>
            svc.transferInstructionContext(Cid(transferInstructionId))
                .flatMap(b => Ok(toContext(b)))

        case POST -> Root / "registry" / "transfer-instruction" / "v2" / transferInstructionId /
            "choice-contexts" / "withdraw" =>
            svc.transferInstructionContext(Cid(transferInstructionId))
                .flatMap(b => Ok(toContext(b)))
    }

    private def toAiFactory(b: ContextBundle): ai.FactoryWithChoiceContext =
        ai.FactoryWithChoiceContext(
          factoryId = b.factoryId.value,
          choiceContext = toContext(b),
        )

    private def toAlFactory(b: ContextBundle): al.FactoryWithChoiceContext =
        al.FactoryWithChoiceContext(
          factoryId = b.factoryId.value,
          choiceContext = al.ChoiceContext(
            choiceContextData = DamlJson.renderChoiceContextData(b.values),
            disclosedContracts = b.disclosures.map(d =>
                al.DisclosedContract(
                  d.templateId.value,
                  d.contractId.value,
                  d.createdEventBlob.value,
                  d.synchronizerId.value
                )
            ),
          ),
        )

    /** A bare `ChoiceContext` (the lifecycle-context response, and the inner value of a
      * `FactoryWithChoiceContext`): the rendered `choiceContextData` + wire disclosures. Uses the
      * `ai` package (identical to `al`).
      */
    private def toContext(b: ContextBundle): ai.ChoiceContext =
        ai.ChoiceContext(
          choiceContextData = DamlJson.renderChoiceContextData(b.values),
          disclosedContracts = b.disclosures.map(d =>
              ai.DisclosedContract(
                d.templateId.value,
                d.contractId.value,
                d.createdEventBlob.value,
                d.synchronizerId.value
              )
          ),
        )
