package tokenstandard.engine

import cats.data.StateT
import daml.splice.api.token.holdingv2.Account
import daml.splice.testing.tokens.testtokenv2.TokenRules
import daml.splice.testing.tokens.testtokenv2.accountconfig.AccountConfig
import daml.splice.testing.tokens.testtokenv2.allocation.TokenAllocationV2
import daml.splice.testing.tokens.testtokenv2.holding.Token
import tokenstandard.PartyId
import tokenstandard.registry.RegistryApi.Error
import tokenstandard.registry.service.*

import scala.jdk.CollectionConverters.*

/** [[AcsSource]] over the engine's contract store, scoped to one registry `admin` — the in-process
  * mirror of `AcsSourceCanton`. Reads the admin's real on-ledger contracts (`TokenRules`,
  * `AccountConfig`, allocations) so the in-memory registry runs the same [[RegistryService]] /
  * [[Assemble]] the live tier does.
  *
  * Disclosure blobs/template ids/synchronizer ids are placeholders: the single-store engine
  * resolves every contract without disclosure, so only the CONTRACT IDS (which drive the
  * choice-context values) need to be real — `EngineLedger.submit` ignores the disclosure list
  * entirely.
  */
final class EngineAcsSource(engine: DamlEngine, admin: PartyId) extends AcsSource[RegEngineM]:

    def tokenRules: RegEngineM[Contract[TokenRulesPayload]] =
        StateT.inspectF { store =>
            engine
                .active(store, TokenRules.TEMPLATE_ID)
                .filter((_, v) => TokenRules.valueDecoder().decode(v).admin == admin.value) match
                case (cid, _) :: Nil => Right(contract(cid, ()))
                case Nil             =>
                    Left(Error.Unexpected("no TokenRules in store — registry not initialized"))
                case many => Left(Error.Unexpected(s"expected 1 TokenRules, found ${many.size}"))
        }

    def accountConfigs: RegEngineM[List[Contract[AccountConfigPayload]]] =
        StateT.inspect { store =>
            engine
                .active(store, AccountConfig.TEMPLATE_ID)
                .map((cid, v) => (cid, AccountConfig.valueDecoder().decode(v)))
                .collect {
                    case (cid, ac) if ac.admin == admin.value =>
                        contract(cid, AccountConfigPayload(ac.account))
                }
        }

    def allocation(cid: Cid): RegEngineM[AllocationDetails] =
        StateT.inspectF { store =>
            allocations(store)
                .collectFirst { case (c, alloc) if c == cid.value => alloc }
                .map { alloc =>
                    AllocationDetails(
                      alloc.allocation.authorizer,
                      alloc.lockedTokens.values.asScala
                          .flatMap(_.asScala)
                          .map(h => Cid(h.contractId))
                          .toList,
                    )
                }
                .toRight(Error.ContractNotFound(cid.value))
        }

    def holdingDisclosures(holdingCids: List[Cid]): RegEngineM[List[Disclosure]] =
        StateT.inspectF { store =>
            val present = engine.active(store, Token.TEMPLATE_ID).map(_._1).toSet
            holdingCids.distinct.filterNot(c => present.contains(c.value)) match
                case missing :: _ => Left(Error.ContractNotFound(missing.value))
                case Nil          => Right(holdingCids.distinct.map(disclosure))
        }

    // Lifecycle-instruction reads: not exercised by the treasury / swap flows.
    def allocationInstruction(cid: Cid): RegEngineM[Account] =
        StateT.liftF(Left(Error.NotImplemented("EngineAcsSource.allocationInstruction")))
    def transferInstruction(cid: Cid): RegEngineM[TransferDetails] =
        StateT.liftF(Left(Error.NotImplemented("EngineAcsSource.transferInstruction")))

    private def allocations(store: EngineStore): List[(String, TokenAllocationV2)] =
        engine
            .active(store, TokenAllocationV2.TEMPLATE_ID)
            .map((cid, v) => (cid, TokenAllocationV2.valueDecoder().decode(v)))

    // Real cid; placeholder blob/templateId/synchronizerId. The engine ignores disclosures, but the
    // registry still parses these into a wire DisclosedContract, so they must be well-formed:
    // `pkg:Module:Entity` and valid base64 (their contents are never used).
    private val placeholderTemplateId = TemplateId("engine:Engine:Contract")
    private val placeholderBlob = Blob("ZW5naW5l") // base64("engine")
    private def contract[A](cid: String, payload: A): Contract[A] =
        Contract(
          Cid(cid),
          placeholderTemplateId,
          payload,
          placeholderBlob,
          SynchronizerId("engine")
        )
    private def disclosure(cid: Cid): Disclosure =
        Disclosure(placeholderTemplateId, cid, placeholderBlob, SynchronizerId("engine"))
