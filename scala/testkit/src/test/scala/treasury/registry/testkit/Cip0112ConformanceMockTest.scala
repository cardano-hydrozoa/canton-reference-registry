package treasury.registry.testkit

import java.time.Instant

import cats.Monad
import cats.instances.either.*

import org.scalacheck.{Gen, Prop, Properties}

import treasury.PartyId
import treasury.TokenStandardHelpers
import treasury.TokenStandardHelpers.basicAccount
import treasury.registry.{RegistryApi, Tracer}
import treasury.registry.service.*

import daml.splice.api.token.allocationinstructionv2.{AllocationFactory_Allocate, AllocationInstruction}
import daml.splice.api.token.allocationv2.{Allocation, SettlementFactory_SettleBatch}
import daml.splice.api.token.holdingv2.Holding
import daml.splice.api.token.transferinstructionv2.TransferInstruction

/** Runs the [[Cip0112Conformance]] suite against the reference [[LocalRegistryApi]] over an
  * in-memory [[MockAcsSource]] — the pure P1–P4 tier (no ledger), across the full V2 endpoint
  * surface: both factories and all seven lifecycle contexts. `F = Either[Throwable, *]`, so a
  * property failure surfaces via `runToProp`'s fold. The live P5 tier exercises the same reference
  * code over a Canton-backed `AcsSource` in the integration tests; the HTTP client is checked in
  * `RegistryClientConformanceSpec`.
  */
object Cip0112ConformanceMockTest extends Properties("cip0112-conformance-mock"):

    private type EitherT[A] = Either[Throwable, A]
    private val eitherMonad: Monad[EitherT] = Monad[EitherT]

    private val admin = PartyId("adminTT2")
    private val alice = PartyId("alice")
    private val bob = PartyId("bob")
    private val parties = List(alice, bob)
    // requestedAt is a don't-care for context assembly; a fixed epoch keeps the property deterministic.
    private val requestedAt = Instant.EPOCH
    // Match the accounts the mock's AccountConfigs carry (basicAccount = owner only, id "").
    private def domainAccount(p: PartyId): Account = Account(Some(p), None, AccountId(""))

    // 3-part `pkg:Module:Entity` template ids + base64 blobs, as LocalRegistryApi.parseIdentifier /
    // disclosuresOf expect.
    private def tid(entity: String): TemplateId =
        TemplateId(s"pkg:Splice.Testing.Tokens.TestTokenV2:$entity")
    private val rules: Contract[TokenRulesPayload] =
        Contract(Cid("rules"), tid("TokenRules"), (), Blob("cnJ1bGVz"), SynchronizerId("sync-1"))
    private val configs: List[Contract[AccountConfigPayload]] =
        parties.map(p =>
            Contract(
              Cid(s"cfg-${p.value}"),
              tid("AccountConfig"),
              AccountConfigPayload(domainAccount(p)),
              Blob("Y2Zn"),
              SynchronizerId("sync-1"),
            )
        )
    private val holdingDisc: Disclosure =
        Disclosure(tid("Holding"), Cid("h1"), Blob("aDE="), SynchronizerId("sync-1"))

    // Lifecycle inputs the mock is configured to resolve (fixed known cids).
    private val mock = MockAcsSource[EitherT](
      rules,
      configs,
      locked = Map(Cid("alloc-1") -> List(holdingDisc)),
      holdings = Map(Cid("h1") -> holdingDisc),
      allocations = Map(Cid("alloc-1") -> AllocationDetails(domainAccount(alice), List(Cid("h1")))),
      allocationInstructions =
          Map(Cid("ai-1") -> AllocationInstructionDetails(domainAccount(alice))),
      transferInstructions = Map(
        Cid("ti-1") -> TransferDetails(domainAccount(alice), domainAccount(bob), List(Cid("h1")))
      ),
    )
    private val impl: RegistryApi[EitherT] =
        LocalRegistryApi[EitherT](
          RegistryService(mock),
          Tracer.noop[EitherT, treasury.registry.RegistryApiEvent],
        )
    private val meta = TokenStandardHelpers.emptyMetadata

    // -- endpoint args -----------------------------------------------------------------------------

    private def allocArg(authorizer: PartyId, holdingCid: String): AllocationFactory_Allocate =
        TokenStandardHelpers.allocationFactoryAllocate(
          TokenStandardHelpers.settlementInfo(List(admin), "settlement-1"),
          TokenStandardHelpers.allocationSpec(admin, authorizer.basicAccount, Nil, false, None),
          requestedAt,
          List(new Holding.ContractId(holdingCid)),
          List(admin),
        )

    private def settleArg(allocationCid: String): SettlementFactory_SettleBatch =
        val leg = TokenStandardHelpers
            .transferLeg("l", alice.basicAccount, bob.basicAccount, BigDecimal(1), "X")
        TokenStandardHelpers.settlementFactorySettleBatch(
          TokenStandardHelpers.settlementInfo(List(admin), "settlement-1"),
          List(leg),
          List(
            TokenStandardHelpers.nonIteratedAllocation(new Allocation.ContractId(allocationCid))
          ),
          List(admin),
        )

    // -- probes ------------------------------------------------------------------------------------

    private def contextView(oc: RegistryApi.OpenApiChoiceContext): ContextView =
        ContextView.context(oc.choiceContext.values, oc.disclosures)

    private val fixture: ConformanceFixture[EitherT] = new ConformanceFixture[EitherT]:
        given monad: Monad[EitherT] = eitherMonad
        def runToProp(fp: EitherT[Prop]): Prop = fp.fold(Prop.exception(_), identity)

        def probes: List[EndpointProbe[EitherT]] = List(
          EndpointProbe[EitherT, AllocationFactory_Allocate](
            "allocation-factory",
            factoryLike0 = true,
            Gen.oneOf(parties)
                .map(p => CidVariant(allocArg(p, "holding-1"), allocArg(p, "holding-2"))),
            arg =>
                impl.getAllocationFactory(arg)
                    .map(ec =>
                        ContextView
                            .factory(ec.factoryCid, ec.arg.extraArgs.context.values, ec.disclosures)
                    ),
          ),
          EndpointProbe[EitherT, SettlementFactory_SettleBatch](
            "settlement-factory",
            factoryLike0 = true,
            Gen.const(CidVariant(settleArg("alloc-1"), settleArg("alloc-2"))),
            arg =>
                impl.getSettlementFactory(arg)
                    .map(ec =>
                        ContextView
                            .factory(ec.factoryCid, ec.arg.extraArgs.context.values, ec.disclosures)
                    ),
          ),
          contextProbe(
            "allocation-withdraw-context",
            new Allocation.ContractId("alloc-1"),
            cid => impl.getAllocationWithdrawContext(cid, meta),
          ),
          contextProbe(
            "allocation-cancel-context",
            new Allocation.ContractId("alloc-1"),
            cid => impl.getAllocationCancelContext(cid, meta),
          ),
          contextProbe(
            "allocation-instruction-withdraw-context",
            new AllocationInstruction.ContractId("ai-1"),
            cid => impl.getAllocationInstructionWithdrawContext(cid, meta),
          ),
          contextProbe(
            "allocation-instruction-accept-context",
            new AllocationInstruction.ContractId("ai-1"),
            cid => impl.getAllocationInstructionAcceptContext(cid, meta),
          ),
          contextProbe(
            "transfer-instruction-accept-context",
            new TransferInstruction.ContractId("ti-1"),
            cid => impl.getTransferInstructionAcceptContext(cid, meta),
          ),
          contextProbe(
            "transfer-instruction-reject-context",
            new TransferInstruction.ContractId("ti-1"),
            cid => impl.getTransferInstructionRejectContext(cid, meta),
          ),
          contextProbe(
            "transfer-instruction-withdraw-context",
            new TransferInstruction.ContractId("ti-1"),
            cid => impl.getTransferInstructionWithdrawContext(cid, meta),
          ),
        )

        private def contextProbe[C](
            name: String,
            cid: C,
            call: C => EitherT[RegistryApi.OpenApiChoiceContext],
        ): EndpointProbe[EitherT] =
            EndpointProbe[EitherT, C](
              name,
              false,
              Gen.const(CidVariant.same(cid)),
              c => call(c).map(contextView)
            )

    include(Cip0112Conformance.suite(fixture))
