package tokenstandard

import daml.splice.api.token.holdingv2.InstrumentId
import org.scalatest.funsuite.AnyFunSuite
import tokenstandard.PartyId
import tokenstandard.engine.DamlEngine
import tokenstandard.engine.EngineLedger
import tokenstandard.engine.EngineRegistry
import tokenstandard.engine.EngineStore

import java.time.Instant

/** Scala port of `Splice.Tests.TestHydrozoaCrossRegistrySwap`: an atomic cross-registry swap run
  * against two engine-backed reference registries + the engine-backed reference ledger (the REAL
  * Daml interpreter, in-process). Two registries (distinct admins, distinct `TokenRules`) each back
  * one instrument; the operator settles the X↔Y swap. `Right(())` means every balance checkpoint
  * held.
  */
class CrossRegistrySwapSpec extends AnyFunSuite:

    private val env = SwapEnv(
      registryX = PartyId("registryX"),
      registryY = PartyId("registryY"),
      alice = PartyId("alice"),
      bob = PartyId("bob"),
      operator = PartyId("operator"),
    )

    test("atomic cross-registry swap: alice's X <-> bob's Y"):
        val xId = new InstrumentId(env.registryX.value, "X")
        val yId = new InstrumentId(env.registryY.value, "Y")

        val engine = DamlEngine.load()
        val ledger = new EngineLedger(engine)
        val regX = EngineRegistry(engine, env.registryX, List("X"))
        val regY = EngineRegistry(engine, env.registryY, List("Y"))
        val flow = new CrossRegistrySwapFlow(regX, regY, ledger)

        val program = for
            _ <- ledger.createTokenRules(env.registryX)
            _ <- ledger.createTokenRules(env.registryY)
            _ <- ledger.seedHolding(env.registryX, env.alice, xId, BigDecimal(1000))
            _ <- ledger.seedHolding(env.registryY, env.bob, yId, BigDecimal(1000))
            _ <- flow.run(env, Instant.EPOCH)
        yield ()

        val result = program.run(EngineStore.empty)
        assert(result.isRight, s"cross-registry swap failed: $result")
