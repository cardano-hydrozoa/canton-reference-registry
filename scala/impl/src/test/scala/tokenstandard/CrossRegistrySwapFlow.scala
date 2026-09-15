package tokenstandard

import cats.MonadError
import cats.syntax.all.*
import daml.splice.api.token.allocationinstructionv2.AllocationFactory
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.allocationv2.SettlementFactory
import daml.splice.api.token.holdingv2.InstrumentId
import tokenstandard.ledger.LedgerClient
import tokenstandard.ledger.Submission
import tokenstandard.registry.RegistryApi
import tokenstandard.registry.RegistryApi.Error

import java.time.Instant

/** Parties for a cross-registry swap demo. `registryX` issues X, `registryY` issues Y (distinct
  * admins = distinct registries); `operator` settles.
  */
final case class SwapEnv(
    registryX: PartyId,
    registryY: PartyId,
    alice: PartyId,
    bob: PartyId,
    operator: PartyId,
)

/** Scala port of `Splice.Tests.TestHydrozoaCrossRegistrySwap` — an atomic cross-REGISTRY swap.
  *
  * Alice holds X (from registryX), Bob holds Y (from registryY). Each instrument's allocations are
  * routed to *its own* registry (`regX` vs `regY`, keyed by the instrument's admin — the
  * `MultiRegistry` shape), and the operator settles each registry's batch.
  *
  * The two settlement exercises ride ONE [[tokenstandard.ledger.Submission]] (`settleX *> settleY`
  * — the port of the Daml `exerciseCmd ecX *> exerciseCmd ecY`), so the swap commits as a single
  * atomic transaction across both registries — no cross-synchronizer needed.
  */
final class CrossRegistrySwapFlow[F[_]](
    regX: RegistryApi[F],
    regY: RegistryApi[F],
    ledger: LedgerClient[F],
)(using F: MonadError[F, Error]):
    import TokenStandardHelpers.*

    /** Precondition: Alice holds 1000 X (registryX), Bob holds 1000 Y (registryY). */
    def run(env: SwapEnv, now: Instant): F[Unit] =
        val xId = instrumentId(env.registryX, "X")
        val yId = instrumentId(env.registryY, "Y")

        val settlement = settlementInfo(List(env.operator), "hydrozoa-cross-registry-swap/demo")
        val xLeg = transferLeg(
          "alice-x-to-bob",
          env.alice.basicAccount,
          env.bob.basicAccount,
          BigDecimal(100),
          "X"
        )
        val yLeg = transferLeg(
          "bob-y-to-alice",
          env.bob.basicAccount,
          env.alice.basicAccount,
          BigDecimal(100),
          "Y"
        )

        def createAllocOn(
            reg: RegistryApi[F],
            authorizer: PartyId,
            spec: daml.splice.api.token.allocationv2.AllocationSpecification,
            inputs: List[daml.splice.api.token.holdingv2.Holding.ContractId],
        ): F[Allocation.ContractId] =
            for
                bundle <- reg.getAllocationFactory(
                  allocationFactoryAllocate(settlement, spec, now, inputs, List(authorizer))
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

        def settlementBundle(
            reg: RegistryApi[F],
            legs: List[daml.splice.api.token.allocationv2.TransferLeg],
            allocations: List[daml.splice.api.token.allocationv2.FinalizedAllocation],
        ) =
            reg.getSettlementFactory(
              settlementFactorySettleBatch(settlement, legs, allocations, List(env.operator))
            )

        def settleSubmission(
            bundle: RegistryApi.EnrichedFactoryChoice[
              daml.splice.api.token.allocationv2.SettlementFactory_SettleBatch
            ]
        ) =
            Submission.exercise(
              new SettlementFactory.ContractId(bundle.factoryCid)
                  .exerciseSettlementFactory_SettleBatch(bundle.arg)
            )

        for
            aliceInputsX <- ledger.listHoldingCids(env.alice, env.alice, xId)
            bobInputsY <- ledger.listHoldingCids(env.bob, env.bob, yId)

            _ <- checkUnlocked(env.alice, xId, 1000)
            _ <- checkUnlocked(env.bob, yId, 1000)
            _ <- checkUnlocked(env.alice, yId, 0)
            _ <- checkUnlocked(env.bob, xId, 0)

            // Each leg: sender locks+authorizes via its instrument's registry; receiver authorizes
            // receipt via that same registry.
            aliceSendX <- createAllocOn(
              regX,
              env.alice,
              allocationSpec(
                env.registryX,
                env.alice.basicAccount,
                List(senderSide(xLeg)),
                committed = false,
                None
              ),
              aliceInputsX
            )
            bobRecvX <- createAllocOn(
              regX,
              env.bob,
              allocationSpec(
                env.registryX,
                env.bob.basicAccount,
                List(receiverSide(xLeg)),
                committed = false,
                None
              ),
              Nil
            )
            bobSendY <- createAllocOn(
              regY,
              env.bob,
              allocationSpec(
                env.registryY,
                env.bob.basicAccount,
                List(senderSide(yLeg)),
                committed = false,
                None
              ),
              bobInputsY
            )
            aliceRecvY <- createAllocOn(
              regY,
              env.alice,
              allocationSpec(
                env.registryY,
                env.alice.basicAccount,
                List(receiverSide(yLeg)),
                committed = false,
                None
              ),
              Nil
            )

            // Cross-registry settle: registryX settles the X leg, registryY the Y leg — BOTH in
            // one Submission, i.e. one atomic transaction (the Daml `*>`).
            bundleX <- settlementBundle(
              regX,
              List(xLeg),
              List(nonIteratedAllocation(aliceSendX), nonIteratedAllocation(bobRecvX))
            )
            bundleY <- settlementBundle(
              regY,
              List(yLeg),
              List(nonIteratedAllocation(bobSendY), nonIteratedAllocation(aliceRecvY))
            )
            _ <- ledger.submit(
              env.operator,
              Nil,
              settleSubmission(bundleX) *> settleSubmission(bundleY),
              bundleX.disclosures ++ bundleY.disclosures,
            )

            // The instruments have crossed registries; value is conserved.
            _ <- checkUnlocked(env.alice, xId, 900)
            _ <- checkUnlocked(env.alice, yId, 100)
            _ <- checkUnlocked(env.bob, yId, 900)
            _ <- checkUnlocked(env.bob, xId, 100)
        yield ()

    private def checkUnlocked(owner: PartyId, inst: InstrumentId, expected: Int): F[Unit] =
        ledger.unlockedBalance(owner, owner, inst).flatMap { actual =>
            if actual == BigDecimal(expected) then F.unit
            else
                F.raiseError[Unit](
                  Error.Unexpected(s"$owner unlocked ${inst.id}: expected $expected, got $actual")
                )
        }
