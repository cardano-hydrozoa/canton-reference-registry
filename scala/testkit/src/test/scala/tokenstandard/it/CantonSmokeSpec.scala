package tokenstandard.it

import cats.effect.IO
import cats.effect.Resource
import cats.effect.testing.scalatest.AsyncIOSpec
import com.daml.ledger.javaapi.data.ContractFilter
import com.daml.ledger.rxjava.DamlLedgerClient
import com.dimafeng.testcontainers.GenericContainer
import daml.splice.testing.tokens.testtokenv2.TokenRules
import org.scalatest.funsuite.AsyncFunSuite
import tokenstandard.it.CantonTestTokenOps.*
import tokenstandard.ledger.LedgerClientCanton

import scala.jdk.CollectionConverters.*

/** Phase 2 slice 1: prove the hard part — the cn-quickstart Canton image boots in Docker, uploads
  * our DARs, and is reachable over the gRPC Ledger API via the Java-bindings `DamlLedgerClient`.
  *
  * Gated on `CANTON_IT=1` so the normal `sbt test` loop stays pure/fast (the container pull + boot
  * takes minutes). The gate is read from the sbt server's environment, so start a fresh server with
  * it set. On a standard Docker host:
  * {{{CANTON_IT=1 sbt "testOnly tokenstandard.it.CantonSmokeSpec"}}}
  *
  * In this sandbox (very new Docker + a nixpkgs JVM) one extra knob is needed, unrelated to the
  * code: `-Dapi.version=1.44` (testcontainers' bundled docker-java negotiates API 1.32, which
  * Docker 29 rejects — min 1.40). Ryuk works fine once that is pinned (it starts and reaps the
  * Canton container after the run), so it is left enabled. Full recipe:
  * {{{
  *   sbt shutdown
  *   CANTON_IT=1 JAVA_TOOL_OPTIONS=-Dapi.version=1.44 sbt "testOnly tokenstandard.it.CantonSmokeSpec"
  * }}}
  */
class CantonSmokeSpec extends AsyncFunSuite, AsyncIOSpec:

    private val container: Resource[IO, GenericContainer] =
        Resource.make(IO.blocking { val c = CantonContainer(); c.start(); c })(c =>
            IO.blocking(c.stop())
        )

    private def rawClient(port: Int): Resource[IO, DamlLedgerClient] =
        Resource.make(IO.blocking {
            val c = DamlLedgerClient.newBuilder("localhost", port).build(); c.connect(); c
        })(c => IO.blocking(c.close()))

    private def ledgerClient(port: Int): Resource[IO, LedgerClientCanton] =
        Resource.make(IO.blocking(LedgerClientCanton.connect("localhost", port)))(l =>
            IO.blocking(l.close())
        )

    private val hints = List("alice", "bob", "hydrozoa", "adminTT2")

    test("canton boots, uploads DARs, and serves the Ledger API"):
        assume(
          sys.env.get("CANTON_IT").contains("1"),
          "set CANTON_IT=1 to run Canton integration tests"
        )
        val setup =
            for
                c <- container
                port <- Resource.eval(IO.blocking(c.mappedPort(CantonContainer.LedgerApiPort)))
                client <- rawClient(port)
                ledger <- ledgerClient(port)
            yield (port, client, ledger)

        setup.use { (port, client, ledger) =>
            for
                packageIds <- IO.blocking(
                  client.getPackageClient.listPackages().blockingIterable().asScala.toList
                )
                // Party allocation over the admin gRPC service (rxjava has no party client).
                parties <- IO.blocking(CantonParties.allocate("localhost", port, hints))
                admin = parties("adminTT2")
                // Submit + typed ACS read: create the registry's TokenRules as admin, read it back.
                _ <- ledger.createTokenRules(admin).value.flatMap(IO.fromEither)
                rules <- ledger
                    .activeContractsOf(ContractFilter.of(TokenRules.COMPANION), admin)
                    .value
                    .flatMap(IO.fromEither)
            yield
                assert(
                  packageIds.nonEmpty,
                  "no packages found on the participant — DAR upload failed?"
                )
                assert(parties.keySet == hints.toSet, s"missing parties: $parties")
                assert(parties.values.toSet.size == hints.size, s"party ids not distinct: $parties")
                hints.foreach(h =>
                    assert(
                      parties(h).value.startsWith(h),
                      s"party id for $h not namespaced: ${parties(h)}"
                    )
                )
                assert(rules.size == 1, s"expected exactly 1 TokenRules, got: $rules")
        }
