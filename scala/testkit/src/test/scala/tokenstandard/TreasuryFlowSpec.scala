package tokenstandard

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.data.StateT
import daml.splice.api.token.holdingv2.Account
import daml.splice.api.token.holdingv2.InstrumentId
import org.scalatest.funsuite.AnyFunSuite
import tokenstandard.PartyId
import tokenstandard.TokenStandardHelpers.basicAccount
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

    /** Registry effect: a read of the CURRENT [[LedgerState]] (Kleisli), so allocations the flow
      * creates on [[InMemoryLedger]] resolve by cid — the [[AcsSource]] miss contract holds exactly
      * as against a real ledger.
      */
    private type RegM[A] = Kleisli[ErrOr, LedgerState, A]

    /** [[AcsSource]] over the in-memory ledger's state: allocations resolve from
      * `LedgerState.allocs` (unknown or closed cid raises `ContractNotFound`, closed = archived).
      * The in-memory model has no holding *contracts*, so a resolved allocation locks no
      * disclosable holdings (`holdingCids = Nil` — not a miss) and any non-empty holding-cid
      * request is a miss; `lockedHoldingDisclosures` is the trait default. No account configs —
      * basic accounts have none, cf. `matchConfig` dropping unmatched accounts.
      */
    private def acsSource(rules: Contract[TokenRulesPayload]): AcsSource[RegM] =
        new AcsSource[RegM]:
            def tokenRules: RegM[Contract[TokenRulesPayload]] = Kleisli.pure(rules)
            def accountConfigs: RegM[List[Contract[AccountConfigPayload]]] = Kleisli.pure(Nil)
            def holdingDisclosures(holdingCids: List[Cid]): RegM[List[Disclosure]] =
                holdingCids match
                    case Nil    => Kleisli.pure(Nil)
                    case c :: _ => Kleisli.liftF(Left(Error.ContractNotFound(c.value)))
            def allocation(cid: Cid): RegM[AllocationDetails] =
                Kleisli(s =>
                    s.allocs
                        .get(cid.value)
                        .filterNot(_.closed)
                        .toRight(Error.ContractNotFound(cid.value))
                        .map(rec => AllocationDetails(rec.authorizer.basicAccount, Nil))
                )
            def allocationInstruction(cid: Cid): RegM[Account] =
                Kleisli.liftF(Left(Error.ContractNotFound(cid.value)))
            def transferInstruction(cid: Cid): RegM[TransferDetails] =
                Kleisli.liftF(Left(Error.ContractNotFound(cid.value)))

    /** [[LocalRegistryApi]] over [[acsSource]], lifted into [[LedgerM]] (each registry call reads
      * the ledger state it runs against, mutating nothing). `rulesCid` distinguishes registries in
      * multi-registry specs.
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
        val impl = LocalRegistryApi[RegM](
          RegistryService(acsSource(rules)),
          RegistryMetadata.basic("adminTT2", List("X", "Y")),
        )
        RegistryApi.mapK(impl)(
          new FunctionK[RegM, LedgerM]:
              def apply[A](fa: RegM[A]): LedgerM[A] =
                  StateT { s =>
                      fa.run(s)
                          .left
                          .map {
                              case e: Error => e
                              case t => Error.Unexpected(Option(t.getMessage).getOrElse(t.toString))
                          }
                          .map(a => (s, a))
                  }
        )
