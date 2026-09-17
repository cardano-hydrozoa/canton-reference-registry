package tokenstandard

import daml.splice.api.token.holdingv2.InstrumentId
import org.scalatest.funsuite.AnyFunSuite
import tokenstandard.PartyId
import tokenstandard.engine.DamlEngine
import tokenstandard.engine.EngineLedger
import tokenstandard.engine.EngineRegistry
import tokenstandard.engine.EngineStore

import java.time.Instant

/** Port of `Splice.Tests.TestHydrozoaTreasury.test`: the treasury allocation workflow run against
  * the REFERENCE registry ([[EngineRegistry]]) + the engine-backed reference ledger
  * ([[EngineLedger]]) — the REAL Daml interpreter in-process, no Canton and no effect runtime, so
  * the whole flow is a `State` transition over the engine's contract store. The same flow runs live
  * in `CantonTreasuryFlowSpec` with only the `AcsSource` and ledger swapped. The flow's inline
  * balance checkpoints do the asserting; a `Right(())` means every checkpoint held.
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

        val engine = DamlEngine.load()
        val ledger = new EngineLedger(engine)
        val registry = EngineRegistry(engine, env.admin, List("X", "Y"))
        val flow = new TreasuryFlow(registry, ledger)

        // Setup on the real ledger: deploy the registry's TokenRules, then mint alice/bob their
        // starting holdings (a direct Token create — the seed analogue of the mint flow).
        val program = for
            _ <- ledger.createTokenRules(env.admin)
            _ <- ledger.seedHolding(env.admin, env.alice, xId, BigDecimal(1000))
            _ <- ledger.seedHolding(env.admin, env.bob, yId, BigDecimal(1000))
            _ <- flow.run(env, Instant.EPOCH)
        yield ()

        val result = program.run(EngineStore.empty)
        assert(result.isRight, s"treasury flow failed: $result")
