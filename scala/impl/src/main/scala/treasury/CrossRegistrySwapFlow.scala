package treasury

import cats.MonadError
import cats.syntax.all.*
import daml.splice.api.token.allocationv2.Allocation
import daml.splice.api.token.holdingv2.InstrumentId
import treasury.ledger.LedgerClient
import treasury.registry.RegistryApi
import treasury.registry.RegistryApi.Error

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
  * On Canton the two settlement exercises go in a single submission (one atomic transaction), so
  * the swap is all-or-nothing across registries — no cross-synchronizer needed. The in-memory
  * ledger used in tests applies them sequentially; it doesn't enforce that atomicity, which is
  * validated in the Canton integration.
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
                cid <- ledger.exerciseAllocationFactory(authorizer, bundle)
            yield cid

        def settleOn(
            reg: RegistryApi[F],
            legs: List[daml.splice.api.token.allocationv2.TransferLeg],
            allocations: List[daml.splice.api.token.allocationv2.FinalizedAllocation],
        ): F[Unit] =
            for
                bundle <- reg.getSettlementFactory(
                  settlementFactorySettleBatch(settlement, legs, allocations, List(env.operator))
                )
                _ <- ledger.exerciseSettlementFactory(env.operator, bundle)
            yield ()

        for
            aliceInputsX <- ledger.listHoldingCids(env.alice, xId)
            bobInputsY <- ledger.listHoldingCids(env.bob, yId)

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

            // Cross-registry settle: registryX settles the X leg, registryY the Y leg (one atomic
            // submission on Canton).
            _ <- settleOn(
              regX,
              List(xLeg),
              List(nonIteratedAllocation(aliceSendX), nonIteratedAllocation(bobRecvX))
            )
            _ <- settleOn(
              regY,
              List(yLeg),
              List(nonIteratedAllocation(bobSendY), nonIteratedAllocation(aliceRecvY))
            )

            // The instruments have crossed registries; value is conserved.
            _ <- checkUnlocked(env.alice, xId, 900)
            _ <- checkUnlocked(env.alice, yId, 100)
            _ <- checkUnlocked(env.bob, yId, 900)
            _ <- checkUnlocked(env.bob, xId, 100)
        yield ()

    private def checkUnlocked(owner: PartyId, inst: InstrumentId, expected: Int): F[Unit] =
        ledger.unlockedBalance(owner, inst).flatMap { actual =>
            if actual == BigDecimal(expected) then F.unit
            else
                F.raiseError[Unit](
                  Error.Unexpected(s"$owner unlocked ${inst.id}: expected $expected, got $actual")
                )
        }
