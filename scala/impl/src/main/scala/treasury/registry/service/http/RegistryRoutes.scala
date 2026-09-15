package treasury.registry.service.http

import cats.effect.Concurrent
import cats.syntax.all.*
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import treasury.registry.openapi.alloc.models as al
import treasury.registry.openapi.allocinstr.models as ai
import treasury.registry.service.*

/** http4s routes serving the two CIP-0112 registry factory endpoints the treasury / cross-registry
  * swap flow exercises, backed by a [[RegistryService]]:
  *   - POST /registry/allocation-instruction/v2/allocation-factory (allocation factory)
  *   - POST /registry/allocation/v2/settlement-factory (settlement factory)
  *
  * The response DTOs are generated per-spec (identical shapes in distinct packages), so each route
  * maps [[ContextBundle]] into its own spec's `FactoryWithChoiceContext`. The transfer-instruction
  * / metadata / choice-context lifecycle endpoints are added when a flow needs them.
  */
final class RegistryRoutes[F[_]: Concurrent](svc: RegistryService[F]) extends Http4sDsl[F]:

    val routes: HttpRoutes[F] = HttpRoutes.of[F] {
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
    }

    private def toAiFactory(b: ContextBundle): ai.FactoryWithChoiceContext =
        ai.FactoryWithChoiceContext(
          factoryId = b.factoryId.value,
          choiceContext = ai.ChoiceContext(
            choiceContextData = DamlJson.renderChoiceContextData(b.values),
            disclosedContracts = b.disclosures.map(d =>
                ai.DisclosedContract(
                  d.templateId.value,
                  d.contractId.value,
                  d.createdEventBlob.value,
                  d.synchronizerId.value
                )
            ),
          ),
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
