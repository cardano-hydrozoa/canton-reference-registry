package tokenstandard.it

import cats.effect.Clock
import cats.effect.IO
import cats.effect.Resource
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.funsuite.AsyncFunSuite
import tokenstandard.CrossRegistrySwapFlow
import tokenstandard.PartyId
import tokenstandard.SwapEnv
import tokenstandard.it.CantonTestTokenOps.*
import tokenstandard.ledger.CantonM
import tokenstandard.ledger.LedgerClientCanton
import tokenstandard.registry.RegistryApi
import tokenstandard.registry.service.AcsSourceCanton
import tokenstandard.registry.service.LocalRegistryApi
import tokenstandard.registry.service.RegistryMetadata
import tokenstandard.registry.service.RegistryService

/** Live validation of CIP-0112's core use case: an ATOMIC cross-registry DvP swap. Two registries —
  * two admin parties, each with its own `TokenRules` — on one participant; the unmodified
  * [[CrossRegistrySwapFlow]] settles Alice's X against Bob's Y with both `SettlementFactory`
  * exercises riding ONE `Submission` (one transaction). This is the claim the Daml original makes
  * with `exerciseCmd ecX *> exerciseCmd ecY`, proven here over the Ledger API rather than Daml
  * Script. Gated on `CANTON_IT=1` (see [[CantonSmokeSpec]] for the sandbox run recipe).
  */
class CantonSwapSpec extends AsyncFunSuite, AsyncIOSpec, CantonItFixture:

    test("atomic cross-registry swap (two registries, one transaction) on live Canton"):
        requireCantonIt()

        val partyHints = List("registryXS", "registryYS", "aliceS", "bobS", "operatorS")
        val setup =
            for
                container <- cantonContainer
                port <- Resource.eval(portOf(container))
                parties <- Resource.eval(
                  IO.blocking(CantonParties.allocate("localhost", port, partyHints))
                )
                ledger <- ledgerClient("localhost", port)
            yield (parties, ledger)

        setup
            .use { (parties: Map[String, PartyId], ledger: LedgerClientCanton) =>
                val env = SwapEnv(
                  registryX = parties("registryXS"),
                  registryY = parties("registryYS"),
                  alice = parties("aliceS"),
                  bob = parties("bobS"),
                  operator = parties("operatorS"),
                )
                def registry(admin: PartyId, instrument: String): RegistryApi[CantonM] =
                    RegistryApi.mapK(
                      LocalRegistryApi[IO](
                        RegistryService(AcsSourceCanton(ledger, admin)),
                        RegistryMetadata.basic(admin.value, List(instrument)),
                      )
                    )(liftIO)
                val flow = new CrossRegistrySwapFlow[CantonM](
                  registry(env.registryX, "X"),
                  registry(env.registryY, "Y"),
                  ledger,
                )
                for
                    now <- Clock[IO].realTimeInstant
                    // Two registries: each admin gets its own TokenRules and mints its own
                    // instrument (each admin only sees — and each registry only reads — its own).
                    _ <- run(ledger.createTokenRules(env.registryX))
                    _ <- run(ledger.createTokenRules(env.registryY))
                    _ <- run(ledger.mint(env.registryX, env.alice, "X", BigDecimal(1000), now))
                    _ <- run(ledger.mint(env.registryY, env.bob, "Y", BigDecimal(1000), now))
                    // The flow's balance checkpoints assert the swap end-to-end; the settle step
                    // submits both registries' SettlementFactory exercises as one transaction.
                    _ <- run(flow.run(env, now))
                yield ()
            }
            .asserting(_ => succeed)
