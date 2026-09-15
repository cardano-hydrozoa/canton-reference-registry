package tokenstandard

import daml.splice.api.token.holdingv2.InstrumentId
import org.scalatest.funsuite.AnyFunSuite
import tokenstandard.PartyId
import tokenstandard.ledger.InMemoryLedger
import tokenstandard.ledger.LedgerM
import tokenstandard.ledger.LedgerState

import java.time.Instant

/** Scala port of `Splice.Tests.TestHydrozoaCrossRegistrySwap`: an atomic cross-registry swap run
  * against two in-memory reference registries + the in-memory ledger. Two registries (distinct
  * admins, distinct `TokenRules`) each back one instrument; the operator settles the X↔Y swap.
  * `Right(())` means every balance checkpoint held.
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

        val seed = LedgerState.seed(
          List(
            (env.alice, xId, BigDecimal(1000)),
            (env.bob, yId, BigDecimal(1000)),
          )
        )
        val regX = TestRegistries.inMemory("rules-x")
        val regY = TestRegistries.inMemory("rules-y")
        val flow = new CrossRegistrySwapFlow[LedgerM](regX, regY, InMemoryLedger)

        val result = flow.run(env, Instant.EPOCH).run(seed)

        assert(result.isRight, s"cross-registry swap failed: $result")
