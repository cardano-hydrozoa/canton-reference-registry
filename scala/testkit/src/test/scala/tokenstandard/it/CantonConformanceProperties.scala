package tokenstandard.it

import cats.effect.Clock
import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import daml.splice.api.token.allocationinstructionv2.AllocationFactory
import daml.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.allocationv2.SettlementFactory
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatchResult
import daml.splice.api.token.allocationv2.SettlementInfo
import daml.splice.api.token.allocationv2.TransferLeg
import daml.splice.api.token.holdingv2.Holding
import org.scalacheck.Prop
import org.scalacheck.Properties
import org.scalacheck.PropertyM
import org.scalacheck.Test
import tokenstandard.PartyId
import tokenstandard.TokenStandardHelpers
import tokenstandard.TokenStandardHelpers.basicAccount
import tokenstandard.it.CantonTestTokenOps.*
import tokenstandard.ledger.LedgerClientCanton
import tokenstandard.registry.RegistryApi
import tokenstandard.registry.service.LocalRegistryApi
import tokenstandard.registry.service.RegistryMetadata
import tokenstandard.registry.service.RegistryService

/** CIP-0112 live-ledger acceptance (P5): the reference [[LocalRegistryApi]] over a real Canton ACS
  * assembles the factory choice contexts + disclosures, and the ledger must accept the exercises.
  * This is the one property that needs a running ledger; P1–P4 run purely against the mock in
  * [[tokenstandard.registry.testkit.Cip0112ConformanceMockTest]].
  *
  * One end-to-end flow is driven against a single booted ledger, each step feeding the next:
  *   1. Mint 100 X to `sender` (a non-admin holder).
  *   2. Funded sender-side deposit — `sender` locks the minted holding behind a leg paying
  *      `receiver` (non-empty leg + real `inputHoldingCids`). Validates the allocation-factory
  *      `AnyValue`/`ChoiceContext` encoding: the ledger resolves the context and locks the holding.
  *   3. `receiver` authorizes receipt (receiver-side allocation, locks nothing).
  *   4. Settlement — a *neutral* executor (owning none of the contracts) settles the batch. Because
  *      it relies entirely on the assembled disclosures, this validates
  *      [[AcsSourceCanton.lockedHoldingDisclosures]] (the port of
  *      `getLockedTokensForAllocationsD`): the executor can only lock/move the deposit's holdings
  *      if they were disclosed correctly.
  *   5. Fundless iterated pool — empty legs + `nextIterationFunding = Some(...)`, the degenerate
  *      context shape, kept as a regression on the empty-`accountConfigs` path.
  *
  * The whole property is a single `IO` — the container is a `Resource` acquired *inside* it via
  * `PropertyM.useResource`, and `PropertyM.monadicIO` runs it once at the ScalaCheck edge (no
  * `unsafeRunSync`, no object-init side effects, no manual teardown). `minSuccessfulTests(1)` keeps
  * it to a single boot. Gated on `CANTON_IT=1` — without it no property is registered and Docker is
  * never touched (see [[CantonSmokeSpec]] for the sandbox run recipe).
  */
object CantonConformanceProperties extends Properties("cip0112-canton-acceptance"):

    override def overrideParameters(p: Test.Parameters): Test.Parameters =
        p.withMinSuccessfulTests(1).withWorkers(1)

    private given unitToProp: (Unit => Prop) = _ => Prop.proved

    private final case class CantonEnv(
        impl: RegistryApi[IO],
        ledger: LedgerClientCanton,
        admin: PartyId,
        sender: PartyId,
        receiver: PartyId,
        executor: PartyId,
        requestedAt: java.time.Instant,
    ):
        /** getAllocationFactory (the code under test) → generic [[LedgerClient.exercise]] as
          * `actAs`. The typed choice result must be `Completed`; its allocation cid is returned —
          * live-testing the exercise-result path, not just ledger acceptance.
          */
        def allocate(actAs: PartyId, arg: AllocationFactory_Allocate): IO[Allocation.ContractId] =
            for
                r <- impl.getAllocationFactory(arg)
                ex <- ledger
                    .exercise(
                      List(actAs),
                      Nil,
                      new AllocationFactory.ContractId(r.factoryCid)
                          .exerciseAllocationFactory_Allocate(r.arg),
                      r.disclosures,
                    )
                    .value
                    .flatMap(IO.fromEither)
                cid <- IO.fromEither(
                  TokenStandardHelpers
                      .completedAllocation(ex.exerciseResult)
                      .left
                      .map(new RuntimeException(_))
                )
            yield cid

        /** getSettlementFactory (the code under test) → generic [[LedgerClient.exercise]] as
          * `actAs`, returning the typed batch result.
          */
        def settle(
            actAs: PartyId,
            arg: SettlementFactory_SettleBatch,
        ): IO[SettlementFactory_SettleBatchResult] =
            for
                r <- impl.getSettlementFactory(arg)
                ex <- ledger
                    .exercise(
                      List(actAs),
                      Nil,
                      new SettlementFactory.ContractId(r.factoryCid)
                          .exerciseSettlementFactory_SettleBatch(r.arg),
                      r.disclosures,
                    )
                    .value
                    .flatMap(IO.fromEither)
            yield ex.exerciseResult

        /** Mint into the `sender` party (a non-admin regular account, as the reference does — the
          * mint's TIA_Accept needs both accounts' parties, which degenerates if receiver == admin).
          */
        def mint(instrument: String, amount: BigDecimal): IO[List[Holding.ContractId]] =
            ledger.mint(admin, sender, instrument, amount, requestedAt).value.flatMap(IO.fromEither)

        /** The live Allocation cids authorized by `sender` and `receiver` (as seen by the admin).
          */
        def allocationCids: IO[List[Allocation.ContractId]] =
            (for
                s <- ledger.activeAllocations(admin, sender)
                r <- ledger.activeAllocations(admin, receiver)
            yield s ++ r).value.flatMap(IO.fromEither)

    /** A single-shot allocation authorizing (and, for the sender side, locking `inputs` behind) one
      * side of `leg`. `admin` is the registry/instrument admin recorded in the spec; the authorizer
      * is `party`'s basic account (no AccountConfig); `actors`/exerciser is `party`.
      */
    private def oneSidedArg(
        admin: PartyId,
        party: PartyId,
        settlement: SettlementInfo,
        side: daml.splice.api.token.allocationv2.TransferLegSide,
        inputs: List[Holding.ContractId],
        requestedAt: java.time.Instant,
    ): AllocationFactory_Allocate =
        TokenStandardHelpers.allocationFactoryAllocate(
          settlement,
          TokenStandardHelpers.allocationSpec(admin, party.basicAccount, List(side), false, None),
          requestedAt,
          inputs,
          List(party),
        )

    /** Settle `leg` atomically, backed by `allocationCids` (each a non-iterated single-shot
      * allocation). `executor` runs the batch and must equal `settlement.executors`.
      */
    private def settleArg(
        executor: PartyId,
        settlement: SettlementInfo,
        leg: TransferLeg,
        allocationCids: List[Allocation.ContractId],
    ): SettlementFactory_SettleBatch =
        TokenStandardHelpers.settlementFactorySettleBatch(
          settlement,
          List(leg),
          allocationCids.map(TokenStandardHelpers.nonIteratedAllocation),
          List(executor),
        )

    /** A fundless iterated (pool) allocation: no legs, `nextIterationFunding = Some(...)` — locks
      * no holdings (so `inputHoldingCids` is empty; a real ledger rejects synthetic cids).
      */
    private def iteratedArg(
        admin: PartyId,
        requestedAt: java.time.Instant
    ): AllocationFactory_Allocate =
        TokenStandardHelpers.allocationFactoryAllocate(
          TokenStandardHelpers.settlementInfo(List(admin), "settlement-iterated"),
          TokenStandardHelpers
              .allocationSpec(
                admin,
                admin.basicAccount,
                Nil,
                false,
                Some(Map.empty[String, BigDecimal])
              ),
          requestedAt,
          Nil,
          List(admin),
        )

    /** Boot Canton, connect, create the registry's TokenRules, and build the reference impl — all
      * as a `Resource` so the container/ledger are released when the property finishes.
      */
    private val cantonEnv: Resource[IO, CantonEnv] =
        for
            container <- Resource.make(IO.blocking {
                // The native-ScalaCheck framework runs properties on a work-stealing pool whose threads
                // (and the cats-effect blocking threads spawned under them) carry a context classloader
                // that can't see testcontainers' Docker-strategy classes → "Could not find a valid
                // Docker environment". Restore the classloader that loaded testcontainers before booting.
                Thread.currentThread.setContextClassLoader(
                  classOf[org.testcontainers.DockerClientFactory].getClassLoader
                )
                val c = CantonContainer(); c.start(); c
            })(c => IO.blocking(c.stop()))
            port <- Resource.eval(IO.blocking(container.mappedPort(CantonContainer.LedgerApiPort)))
            parties <- Resource.eval(
              IO.blocking(
                CantonParties
                    .allocate(
                      "localhost",
                      port,
                      List("adminTT2", "senderTT2", "receiverTT2", "execTT2")
                    )
              )
            )
            admin = parties("adminTT2")
            sender = parties("senderTT2")
            receiver = parties("receiverTT2")
            executor = parties("execTT2")
            ledger <- Resource.make(IO.blocking(LedgerClientCanton.connect("localhost", port)))(l =>
                IO.blocking(l.close())
            )
            _ <- Resource.eval(ledger.createTokenRules(admin).value.flatMap(IO.fromEither))
            requestedAt <- Resource.eval(Clock[IO].realTimeInstant)
        yield CantonEnv(
          LocalRegistryApi[IO](
            RegistryService(AcsSourceCanton(ledger, admin)),
            RegistryMetadata.basic(admin.value, List("X")),
          ),
          ledger,
          admin,
          sender,
          receiver,
          executor,
          requestedAt,
        )

    if sys.env.get("CANTON_IT").contains("1") then
        property("factory contexts assembled + exercised on a live Canton ledger (P5)") =
            PropertyM.monadicIO {
                PropertyM.useResource(cantonEnv) { env =>
                    val settlement =
                        TokenStandardHelpers.settlementInfo(List(env.executor), "settle-batch")
                    val leg = TokenStandardHelpers
                        .transferLeg(
                          "swap",
                          env.sender.basicAccount,
                          env.receiver.basicAccount,
                          BigDecimal(100),
                          "X"
                        )
                    for
                        holdings <- PropertyM.run(env.mint("X", BigDecimal(100)))
                        // funded sender-side deposit — locks the minted 100 X (allocation-factory acceptance)
                        _ <- PropertyM.run(
                          env.allocate(
                            env.sender,
                            oneSidedArg(
                              env.admin,
                              env.sender,
                              settlement,
                              TokenStandardHelpers.senderSide(leg),
                              holdings,
                              env.requestedAt,
                            ),
                          )
                        )
                        // receiver authorizes receipt (locks nothing)
                        _ <- PropertyM.run(
                          env.allocate(
                            env.receiver,
                            oneSidedArg(
                              env.admin,
                              env.receiver,
                              settlement,
                              TokenStandardHelpers.receiverSide(leg),
                              Nil,
                              env.requestedAt,
                            ),
                          )
                        )
                        // settle the batch as a neutral executor (settlement-factory + locked-holding
                        // disclosure acceptance) — only the two allocations above are live here
                        allocs <- PropertyM.run(env.allocationCids)
                        settleRes <- PropertyM.run(
                          env.settle(env.executor, settleArg(env.executor, settlement, leg, allocs))
                        )
                        _ <- PropertyM.run(
                          IO.raiseError(
                            new RuntimeException(
                              s"expected 2 allocation settle results, got: $settleRes"
                            )
                          ).unlessA(settleRes.allocationSettleResults.size == 2)
                        )
                        // fundless iterated pool — degenerate context regression
                        _ <- PropertyM.run(
                          env.allocate(env.admin, iteratedArg(env.admin, env.requestedAt))
                        )
                    yield ()
                }
            }
