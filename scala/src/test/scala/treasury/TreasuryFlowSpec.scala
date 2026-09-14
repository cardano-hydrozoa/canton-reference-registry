package treasury

import java.time.Instant

import org.scalatest.funsuite.AnyFunSuite

import treasury.PartyId
import treasury.ledger.{InMemoryLedger, LedgerM, LedgerState}
import treasury.registry.{RegistryApiStub, Tracer}

import daml.splice.api.token.holdingv2.InstrumentId

/** Phase 1 port of `Splice.Tests.TestHydrozoaTreasury.test`: the treasury allocation workflow run
  * against the pure stub registry + in-memory ledger, with no Canton and no effect runtime — the
  * whole flow is a `State` transition. The flow's inline balance checkpoints do the asserting; a
  * `Right(())` means every checkpoint held.
  */
class TreasuryFlowSpec extends AnyFunSuite:

    private val env = TreasuryEnv(
      admin = PartyId("adminTT2"),
      provider = PartyId("provider"),
      alice = PartyId("alice"),
      bob = PartyId("bob"),
      hydrozoa = PartyId("hydrozoa"),
    )

    test("hydrozoa treasury flow: deposit, off-ledger swap, settle, close"):
        val xId = new InstrumentId(env.admin.value, "X")
        val yId = new InstrumentId(env.admin.value, "Y")

        val seed = LedgerState.seed(
          List(
            (env.alice, xId, BigDecimal(1000)),
            (env.bob, yId, BigDecimal(1000)),
          )
        )
        val flow =
            new TreasuryFlow[LedgerM](new RegistryApiStub[LedgerM](Tracer.noop), InMemoryLedger)

        // StateT over Either: run yields Either[Error, (finalState, result)]; a Left is a failed
        // checkpoint.
        val result = flow.run(env, Instant.EPOCH).run(seed)

        assert(result.isRight, s"treasury flow failed: $result")
