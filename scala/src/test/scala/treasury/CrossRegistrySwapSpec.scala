package treasury

import java.time.Instant

import org.scalatest.funsuite.AnyFunSuite

import treasury.PartyId
import treasury.ledger.{InMemoryLedger, LedgerM, LedgerState}
import treasury.registry.{RegistryBackendStub, Tracer}

import daml.splice.api.token.holdingv2.InstrumentId

/** Scala port of `Splice.Tests.TestHydrozoaCrossRegistrySwap`: an atomic cross-registry swap run
  * against the pure stub registries + in-memory ledger. Two registries (distinct admins) each back
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

        val seed = LedgerState.seed(
          List(
            (env.alice, xId, BigDecimal(1000)),
            (env.bob, yId, BigDecimal(1000)),
          )
        )
        val regX = new RegistryBackendStub[LedgerM](Tracer.noop)
        val regY = new RegistryBackendStub[LedgerM](Tracer.noop)
        val flow = new CrossRegistrySwapFlow[LedgerM](regX, regY, InMemoryLedger)

        val result = flow.run(env, Instant.EPOCH).run(seed)

        assert(result.isRight, s"cross-registry swap failed: $result")
