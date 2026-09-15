package tokenstandard

import cats.MonadError
import cats.syntax.all.*
import daml.splice.api.token.allocationinstructionv2.AllocationFactory
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.allocationv2.AllocationSpecification
import daml.splice.api.token.allocationv2.FinalizedAllocation
import daml.splice.api.token.allocationv2.SettlementFactory
import daml.splice.api.token.allocationv2.SettlementFactory_SettleBatchResult
import daml.splice.api.token.allocationv2.SettlementInfo
import daml.splice.api.token.allocationv2.TransferLeg
import daml.splice.api.token.holdingv2.Holding
import daml.splice.api.token.holdingv2.InstrumentId
import tokenstandard.ledger.LedgerClient
import tokenstandard.registry.RegistryApi
import tokenstandard.registry.RegistryApi.Error

import java.time.Instant

/** Parties for a Hydrozoa-style head. `hydrozoa` plays the settlement executor throughout; in a
  * real head this would be a multisig/quorum rather than one party.
  */
final case class TreasuryEnv(
    admin: PartyId,
    provider: PartyId,
    alice: PartyId,
    bob: PartyId,
    hydrozoa: PartyId,
)

/** Scala port of `Splice.Tests.TestHydrozoaTreasury.hydrozoaTreasuryFlow`.
  *
  * Alice deposits 100 X and Bob deposits 100 Y into a committed, iterated treasury allocation
  * authorized by the operator; an L2 swap happens off-ledger; the operator settles, paying Alice
  * 100 Y and Bob 100 X and closing the pool. The balance checkpoints from the Daml test are kept
  * inline as assertions, so running this flow — here against the in-memory ledger, later against a
  * Canton localnet — verifies the same invariants at each step.
  */
final class TreasuryFlow[F[_]](reg: RegistryApi[F], ledger: LedgerClient[F])(using
    F: MonadError[F, Error]
):
    import TokenStandardHelpers.*

    /** Precondition: the ledger already holds Alice's 1000 X and Bob's 1000 Y (minting is a
      * registry-admin concern, done during test/localnet setup).
      */
    def run(env: TreasuryEnv, now: Instant): F[Unit] =
        val admin = env.admin
        val xId = instrumentId(admin, "X")
        val yId = instrumentId(admin, "Y")

        val settlement = settlementInfo(List(env.hydrozoa), "hydrozoa-head/demo")

        // deposit legs (into the treasury) and payout legs (out of it, swapped)
        val aliceDepositLeg = transferLeg(
          "alice-deposit",
          env.alice.basicAccount,
          env.hydrozoa.basicAccount,
          BigDecimal(100),
          "X"
        )
        val bobDepositLeg = transferLeg(
          "bob-deposit",
          env.bob.basicAccount,
          env.hydrozoa.basicAccount,
          BigDecimal(100),
          "Y"
        )
        val alicePayoutLeg = transferLeg(
          "alice-payout",
          env.hydrozoa.basicAccount,
          env.alice.basicAccount,
          BigDecimal(100),
          "Y"
        )
        val bobPayoutLeg = transferLeg(
          "bob-payout",
          env.hydrozoa.basicAccount,
          env.bob.basicAccount,
          BigDecimal(100),
          "X"
        )

        // single-shot allocations: authorize (and, for senders, lock) one side each
        val aliceDepositAlloc = allocationSpec(
          admin,
          env.alice.basicAccount,
          List(senderSide(aliceDepositLeg)),
          committed = false,
          None
        )
        val bobDepositAlloc = allocationSpec(
          admin,
          env.bob.basicAccount,
          List(senderSide(bobDepositLeg)),
          committed = false,
          None
        )
        val aliceReceiveAlloc = allocationSpec(
          admin,
          env.alice.basicAccount,
          List(receiverSide(alicePayoutLeg)),
          committed = false,
          None
        )
        val bobReceiveAlloc = allocationSpec(
          admin,
          env.bob.basicAccount,
          List(receiverSide(bobPayoutLeg)),
          committed = false,
          None
        )

        // the pooled treasury allocation: iterated + committed, no legs of its own; the operator
        // finalizes concrete legs at settlement time.
        val treasuryAlloc = allocationSpec(
          admin,
          env.hydrozoa.basicAccount,
          Nil,
          committed = true,
          Some(Map.empty)
        )

        val program: F[Unit] =
            for
                // 1. deposits ----------------------------------------------------------
                aliceInputs <- inputsAcross(env.alice, List(xId, yId))
                bobInputs <- inputsAcross(env.bob, List(xId, yId))

                // Preflight: only the minted balances exist; nothing is locked yet.
                _ <- checkBalances(env.alice, List(xId -> 1000, yId -> 0), List(xId -> 0, yId -> 0))
                _ <- checkBalances(env.bob, List(xId -> 0, yId -> 1000), List(xId -> 0, yId -> 0))
                _ <- checkBalances(env.hydrozoa, List(xId -> 0, yId -> 0), List(xId -> 0, yId -> 0))

                aliceDepositCid <- createAlloc(
                  env.alice,
                  aliceDepositAlloc,
                  aliceInputs,
                  settlement,
                  now
                )
                bobDepositCid <- createAlloc(env.bob, bobDepositAlloc, bobInputs, settlement, now)
                treasuryCid0 <- createAlloc(env.hydrozoa, treasuryAlloc, Nil, settlement, now)

                // Post-allocation, pre-settle: each sender's 100 is now locked into its deposit
                // allocation (still owned by the sender); the treasury holds nothing yet.
                _ <- checkBalances(
                  env.alice,
                  List(xId -> 900, yId -> 0),
                  List(xId -> 100, yId -> 0)
                )
                _ <- checkBalances(env.bob, List(xId -> 0, yId -> 900), List(xId -> 0, yId -> 100))
                _ <- checkBalances(env.hydrozoa, List(xId -> 0, yId -> 0), List(xId -> 0, yId -> 0))

                // Operator settles: the senders' funds move into the pooled treasury allocation,
                // whose reserved funding rolls forward to hold them. Treasury is listed first, so
                // its next-iteration allocation is the head result.
                depositResult <- settleBatch(
                  env.hydrozoa,
                  List(aliceDepositLeg, bobDepositLeg),
                  List(
                    finalizedAllocation(
                      treasuryCid0,
                      List(receiverSide(aliceDepositLeg), receiverSide(bobDepositLeg)),
                      Some(Map("X" -> BigDecimal(100), "Y" -> BigDecimal(100))),
                    ),
                    nonIteratedAllocation(aliceDepositCid),
                    nonIteratedAllocation(bobDepositCid),
                  ),
                  settlement,
                )
                treasuryCid1 <- F.fromOption(
                  nextIterationAllocations(depositResult).headOption.flatten,
                  Error.Unexpected("deposit settle returned no next-iteration treasury allocation"),
                )

                // Post-deposit-settle: the locked funds now sit under hydrozoa (the pool's reserved
                // funding); the senders keep their unlocked remainders.
                _ <- checkBalances(env.alice, List(xId -> 900, yId -> 0), List(xId -> 0, yId -> 0))
                _ <- checkBalances(env.bob, List(xId -> 0, yId -> 900), List(xId -> 0, yId -> 0))
                _ <- checkBalances(
                  env.hydrozoa,
                  List(xId -> 0, yId -> 0),
                  List(xId -> 100, yId -> 100)
                )

                // 2. L2 trade happens off-ledger: Alice and Bob swap X <-> Y -----------

                // 3. withdrawal --------------------------------------------------------
                // Each withdrawer signals intent by authorizing receipt of their payout.
                aliceReceiveCid <- createAlloc(env.alice, aliceReceiveAlloc, Nil, settlement, now)
                bobReceiveCid <- createAlloc(env.bob, bobReceiveAlloc, Nil, settlement, now)

                // Authorizing receipt locks nothing, so balances are unchanged.
                _ <- checkBalances(env.alice, List(xId -> 900, yId -> 0), List(xId -> 0, yId -> 0))
                _ <- checkBalances(env.bob, List(xId -> 0, yId -> 900), List(xId -> 0, yId -> 0))
                _ <- checkBalances(
                  env.hydrozoa,
                  List(xId -> 0, yId -> 0),
                  List(xId -> 100, yId -> 100)
                )

                // Operator pays out the swapped instruments and closes the pool
                // (nextIterationFunding = None ends iteration).
                _ <- settleBatch(
                  env.hydrozoa,
                  List(alicePayoutLeg, bobPayoutLeg),
                  List(
                    finalizedAllocation(
                      treasuryCid1,
                      List(senderSide(alicePayoutLeg), senderSide(bobPayoutLeg)),
                      None,
                    ),
                    nonIteratedAllocation(aliceReceiveCid),
                    nonIteratedAllocation(bobReceiveCid),
                  ),
                  settlement,
                )

                // 4. assertions: the swap took effect and value is conserved -----------
                _ <- checkBalances(
                  env.alice,
                  List(xId -> 900, yId -> 100),
                  List(xId -> 0, yId -> 0)
                )
                _ <- checkBalances(env.bob, List(xId -> 100, yId -> 900), List(xId -> 0, yId -> 0))
                _ <- checkBalances(env.hydrozoa, List(xId -> 0, yId -> 0), List(xId -> 0, yId -> 0))

                // the treasury pool is closed
                remaining <- ledger.activeAllocations(env.hydrozoa, env.hydrozoa)
                _ <-
                    if remaining.isEmpty then F.unit
                    else
                        F.raiseError[Unit](
                          Error.Unexpected(
                            s"treasury not closed: ${remaining.size} allocation(s) remain"
                          )
                        )
            yield ()

        program

    // --- helpers ---------------------------------------------------------------

    private def createAlloc(
        authorizer: PartyId,
        spec: AllocationSpecification,
        inputs: List[Holding.ContractId],
        settlement: SettlementInfo,
        now: Instant,
    ): F[Allocation.ContractId] =
        for
            // The registry locates the factory contract and assembles the choice context +
            // disclosures; exercising the returned bundle yields the Allocation.
            bundle <- reg.getAllocationFactory(
              TokenStandardHelpers.allocationFactoryAllocate(
                settlement,
                spec,
                now,
                inputs,
                List(authorizer)
              )
            )
            exercised <- ledger.exercise(
              authorizer,
              Nil,
              new AllocationFactory.ContractId(bundle.factoryCid)
                  .exerciseAllocationFactory_Allocate(bundle.arg),
              bundle.disclosures,
            )
            cid <- F.fromEither(
              completedAllocation(exercised.exerciseResult).leftMap(Error.Unexpected(_))
            )
        yield cid

    private def settleBatch(
        executor: PartyId,
        legs: List[TransferLeg],
        allocations: List[FinalizedAllocation],
        settlement: SettlementInfo,
    ): F[SettlementFactory_SettleBatchResult] =
        for
            bundle <- reg.getSettlementFactory(
              TokenStandardHelpers.settlementFactorySettleBatch(
                settlement,
                legs,
                allocations,
                List(executor)
              )
            )
            exercised <- ledger.exercise(
              executor,
              Nil,
              new SettlementFactory.ContractId(bundle.factoryCid)
                  .exerciseSettlementFactory_SettleBatch(bundle.arg),
              bundle.disclosures,
            )
        yield exercised.exerciseResult

    private def inputsAcross(
        owner: PartyId,
        instruments: List[InstrumentId]
    ): F[List[Holding.ContractId]] =
        instruments.flatTraverse(inst => ledger.listHoldingCids(owner, owner, inst))

    /** Assert a party's unlocked and locked balances across several instruments (Daml's
      * `checkBalances`). An instrument's expected total is a plain number; `0` asserts empty.
      */
    private def checkBalances(
        owner: PartyId,
        unlocked: List[(InstrumentId, Int)],
        locked: List[(InstrumentId, Int)],
    ): F[Unit] =
        for
            _ <- unlocked.traverse_((inst, exp) =>
                ledger
                    .unlockedBalance(owner, owner, inst)
                    .flatMap(assertEq(owner, inst, "unlocked", _, exp))
            )
            _ <- locked.traverse_((inst, exp) =>
                ledger
                    .lockedBalance(owner, owner, inst)
                    .flatMap(assertEq(owner, inst, "locked", _, exp))
            )
        yield ()

    private def assertEq(
        owner: PartyId,
        inst: InstrumentId,
        kind: String,
        actual: BigDecimal,
        expected: Int
    ): F[Unit] =
        if actual == BigDecimal(expected) then F.unit
        else
            F.raiseError[Unit](
              Error.Unexpected(s"$owner $kind ${inst.id}: expected $expected, got $actual")
            )
