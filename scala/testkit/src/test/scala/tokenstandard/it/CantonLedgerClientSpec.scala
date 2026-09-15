package tokenstandard.it

import cats.effect.Clock
import cats.effect.IO
import cats.effect.Resource
import cats.effect.testing.scalatest.AsyncIOSpec
import com.dimafeng.testcontainers.GenericContainer
import daml.splice.api.token.allocationinstructionv2.AllocationFactory
import org.scalatest.funsuite.AsyncFunSuite
import tokenstandard.PartyId
import tokenstandard.TokenStandardHelpers
import tokenstandard.TokenStandardHelpers.basicAccount
import tokenstandard.it.CantonTestTokenOps.*
import tokenstandard.ledger.CantonM
import tokenstandard.ledger.LedgerClient
import tokenstandard.ledger.LedgerClientCanton
import tokenstandard.registry.service.LocalRegistryApi
import tokenstandard.registry.service.RegistryMetadata
import tokenstandard.registry.service.RegistryService

/** Live-Canton acceptance for the [[LedgerClient]] surface itself: every call goes through the
  * TRAIT (typed as `LedgerClient[CantonM]`), so this proves the production seam — the generic
  * `exercise` returning the typed choice result via `SubmitAndWaitForTransaction` +
  * `LEDGER_EFFECTS`, and the balance/holding/allocation ACS reads — against a real ledger.
  *
  * One focused flow: mint 1000 X to a non-admin owner (mint requires a non-admin receiver), check
  * unlocked/locked balances and holding cids through the trait, then exercise
  * `AllocationFactory_Allocate` generically (context assembled by the reference registry over
  * [[AcsSourceCanton]]) and check the typed `Completed` result, its cid's presence in
  * `activeAllocations`, and the balance shift. Gated on `CANTON_IT=1` (see [[CantonSmokeSpec]] for
  * the sandbox run recipe).
  */
class CantonLedgerClientSpec extends AsyncFunSuite, AsyncIOSpec:

    private val cantonContainer: Resource[IO, GenericContainer] =
        Resource.make(IO.blocking { val c = CantonContainer(); c.start(); c })(c =>
            IO.blocking(c.stop())
        )

    private def ledgerClient(host: String, port: Int): Resource[IO, LedgerClientCanton] =
        Resource.make(IO.blocking(LedgerClientCanton.connect(host, port)))(l =>
            IO.blocking(l.close())
        )

    private def run[A](c: CantonM[A]): IO[A] = c.value.flatMap(IO.fromEither)

    test("LedgerClient trait: mint → balances/holdings → generic allocate → typed result"):
        assume(
          sys.env.get("CANTON_IT").contains("1"),
          "set CANTON_IT=1 to run Canton integration tests"
        )

        val setup =
            for
                container <- cantonContainer
                port <- Resource.eval(
                  IO.blocking(container.mappedPort(CantonContainer.LedgerApiPort))
                )
                parties <- Resource.eval(
                  IO.blocking(CantonParties.allocate("localhost", port, List("adminLC", "ownerLC")))
                )
                ledger <- ledgerClient("localhost", port)
            yield (parties("adminLC"), parties("ownerLC"), ledger)

        setup
            .use { (admin: PartyId, owner: PartyId, ledger: LedgerClientCanton) =>
                val lc: LedgerClient[CantonM] = ledger
                val impl = LocalRegistryApi[IO](
                  RegistryService(AcsSourceCanton(ledger, admin)),
                  RegistryMetadata.basic(admin.value, List("X")),
                )
                val instrument = TokenStandardHelpers.instrumentId(admin, "X")
                for
                    requestedAt <- Clock[IO].realTimeInstant
                    _ <- run(ledger.createTokenRules(admin))
                    holdings <- run(ledger.mint(admin, owner, "X", BigDecimal(1000), requestedAt))
                    unlockedAfterMint <- run(lc.unlockedBalance(owner, owner, instrument))
                    lockedAfterMint <- run(lc.lockedBalance(owner, owner, instrument))
                    listedCids <- run(lc.listHoldingCids(owner, owner, instrument))
                    // generic exercise of AllocationFactory_Allocate: owner locks 300 X behind a
                    // leg paying admin; context + disclosures assembled by the reference registry
                    leg = TokenStandardHelpers.transferLeg(
                      "lc-leg",
                      owner.basicAccount,
                      admin.basicAccount,
                      BigDecimal(300),
                      "X",
                    )
                    arg = TokenStandardHelpers.allocationFactoryAllocate(
                      TokenStandardHelpers.settlementInfo(List(admin), "lc-settlement"),
                      TokenStandardHelpers.allocationSpec(
                        admin,
                        owner.basicAccount,
                        List(TokenStandardHelpers.senderSide(leg)),
                        false,
                        None,
                      ),
                      requestedAt,
                      listedCids,
                      List(owner),
                    )
                    enriched <- impl.getAllocationFactory(arg)
                    exercised <- run(
                      lc.exercise(
                        owner,
                        Nil,
                        new AllocationFactory.ContractId(enriched.factoryCid)
                            .exerciseAllocationFactory_Allocate(enriched.arg),
                        enriched.disclosures,
                      )
                    )
                    allocCid <- IO.fromEither(
                      TokenStandardHelpers
                          .completedAllocation(exercised.exerciseResult)
                          .left
                          .map(new RuntimeException(_))
                    )
                    activeAllocs <- run(lc.activeAllocations(owner, owner))
                    unlockedAfterAlloc <- run(lc.unlockedBalance(owner, owner, instrument))
                    lockedAfterAlloc <- run(lc.lockedBalance(owner, owner, instrument))
                yield (
                  holdings,
                  unlockedAfterMint,
                  lockedAfterMint,
                  listedCids,
                  allocCid,
                  activeAllocs,
                  unlockedAfterAlloc,
                  lockedAfterAlloc,
                )
            }
            .asserting {
                case (
                      holdings,
                      unlockedAfterMint,
                      lockedAfterMint,
                      listedCids,
                      allocCid,
                      activeAllocs,
                      unlockedAfterAlloc,
                      lockedAfterAlloc,
                    ) =>
                    assert(holdings.nonEmpty, "mint must produce unlocked holdings")
                    assert(unlockedAfterMint == BigDecimal(1000), s"got $unlockedAfterMint")
                    assert(lockedAfterMint == BigDecimal(0), s"got $lockedAfterMint")
                    assert(listedCids.nonEmpty, "listHoldingCids must see the minted holding")
                    assert(
                      activeAllocs.contains(allocCid),
                      s"completed allocation $allocCid not in activeAllocations: $activeAllocs",
                    )
                    assert(lockedAfterAlloc == BigDecimal(300), s"got $lockedAfterAlloc")
                    assert(unlockedAfterAlloc == BigDecimal(700), s"got $unlockedAfterAlloc")
            }
