package tokenstandard

import cats.arrow.FunctionK
import cats.data.StateT
import daml.splice.api.token.holdingv2.InstrumentId
import org.scalatest.funsuite.AnyFunSuite
import tokenstandard.PartyId
import tokenstandard.ledger.InMemoryLedger
import tokenstandard.ledger.LedgerM
import tokenstandard.ledger.LedgerState
import tokenstandard.registry.RegistryApi
import tokenstandard.registry.RegistryApi.Error
import tokenstandard.registry.service.*

import java.time.Instant

/** Port of `Splice.Tests.TestHydrozoaTreasury.test`: the treasury allocation workflow run against
  * the REFERENCE registry ([[LocalRegistryApi]] over an in-memory [[MockAcsSource]]) + the
  * in-memory ledger, with no Canton and no effect runtime — the whole flow is a `State` transition.
  * The same flow runs live in `CantonTreasuryFlowSpec` with only the `AcsSource` and ledger
  * swapped. The flow's inline balance checkpoints do the asserting; a `Right(())` means every
  * checkpoint held.
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
        val flow = new TreasuryFlow[LedgerM](TestRegistries.inMemory("rules"), InMemoryLedger)

        // StateT over Either: run yields Either[Error, (finalState, result)]; a Left is a failed
        // checkpoint.
        val result = flow.run(env, Instant.EPOCH).run(seed)

        assert(result.isRight, s"treasury flow failed: $result")

/** The reference registry lifted into the in-memory ledger's effect, shared by the flow specs. */
object TestRegistries:

    private type ErrOr[A] = Either[Throwable, A]

    /** [[LocalRegistryApi]] over a minimal [[MockAcsSource]] (a `TokenRules` contract, no account
      * configs — basic accounts have none, cf. `matchConfig` dropping unmatched accounts), lifted
      * into [[LedgerM]]. `rulesCid` distinguishes registries in multi-registry specs.
      */
    def inMemory(rulesCid: String): RegistryApi[LedgerM] =
        val rules: Contract[TokenRulesPayload] =
            Contract(
              Cid(rulesCid),
              TemplateId("pkg:Splice.Testing.Tokens.TestTokenV2:TokenRules"),
              (),
              Blob("cnVsZXM="), // base64("rules") — disclosuresOf base64-decodes blobs
              SynchronizerId("sync-1"),
            )
        val impl = LocalRegistryApi[ErrOr](
          RegistryService(MockAcsSource[ErrOr](rules, Nil)),
          RegistryMetadata.basic("adminTT2", List("X", "Y")),
        )
        RegistryApi.mapK(impl)(
          new FunctionK[ErrOr, LedgerM]:
              def apply[A](fa: ErrOr[A]): LedgerM[A] =
                  StateT.liftF(fa.left.map {
                      case e: Error => e
                      case t        => Error.Unexpected(Option(t.getMessage).getOrElse(t.toString))
                  })
        )
