package treasury

import cats.effect.unsafe.implicits.global
import daml.splice.api.token.holdingv2.InstrumentId
import org.scalatest.funsuite.AnyFunSuite
import treasury.ledger.InMemoryLedger
import treasury.registry.RegistryBackendStub

import java.time.Instant

/** Phase 1 port of `Splice.Tests.TestHydrozoaTreasury.test`: the treasury allocation workflow run
  * against the pure stub registry + in-memory ledger, with no Canton. The flow's inline balance
  * checkpoints do the asserting; a `Right(())` means every checkpoint held.
  */
class TreasuryFlowSpec extends AnyFunSuite:

    private val env = TreasuryEnv(
      admin = "adminTT2",
      provider = "provider",
      alice = "alice",
      bob = "bob",
      hydrozoa = "hydrozoa",
    )

    test("hydrozoa treasury flow: deposit, off-ledger swap, settle, close"):
        val xId = new InstrumentId(env.admin, "X")
        val yId = new InstrumentId(env.admin, "Y")

        val result = (for
            ledger <- InMemoryLedger.create(
              List(
                (env.alice, xId, BigDecimal(1000)),
                (env.bob, yId, BigDecimal(1000)),
              )
            )
            flow = new TreasuryFlow[cats.effect.IO](new RegistryBackendStub(), ledger)
            out <- flow.run(env, Instant.EPOCH)
        yield out).unsafeRunSync()

        assert(result == Right(()), s"treasury flow failed: $result")
