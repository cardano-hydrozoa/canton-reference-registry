package treasury.it

import scala.jdk.CollectionConverters.*

import cats.effect.unsafe.implicits.global

import com.daml.ledger.javaapi.data.ContractFilter
import com.daml.ledger.rxjava.DamlLedgerClient

import org.scalatest.funsuite.AnyFunSuite

import daml.splice.testing.tokens.testtokenv2.TokenRules

/** Phase 2 slice 1: prove the hard part — the cn-quickstart Canton image boots in Docker, uploads
  * our DARs, and is reachable over the gRPC Ledger API via the Java-bindings `DamlLedgerClient`.
  *
  * Gated on `CANTON_IT=1` so the normal `sbt test` loop stays pure/fast (the container pull + boot
  * takes minutes). The gate is read from the sbt server's environment, so start a fresh server with
  * it set. On a standard Docker host: {{{CANTON_IT=1 sbt "testOnly treasury.it.CantonSmokeSpec"}}}
  *
  * In this sandbox (very new Docker + a nixpkgs JVM) two extra knobs are needed, unrelated to the
  * code: `-Dapi.version=1.44` (testcontainers' bundled docker-java negotiates API 1.32, which
  * Docker 29 rejects — min 1.40) and `TESTCONTAINERS_RYUK_DISABLED=true`. Full recipe:
  * {{{
  *   sbt shutdown
  *   CANTON_IT=1 TESTCONTAINERS_RYUK_DISABLED=true JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
  *     sbt "testOnly treasury.it.CantonSmokeSpec"
  * }}}
  */
class CantonSmokeSpec extends AnyFunSuite:

    test("canton boots, uploads DARs, and serves the Ledger API"):
        assume(
          sys.env.get("CANTON_IT").contains("1"),
          "set CANTON_IT=1 to run Canton integration tests"
        )

        val container = CantonContainer()
        container.start()
        try
            val port = container.mappedPort(CantonContainer.LedgerApiPort)
            val client = DamlLedgerClient.newBuilder("localhost", port).build()
            client.connect()
            try
                val packageIds =
                    client.getPackageClient.listPackages().blockingIterable().asScala.toList
                assert(
                  packageIds.nonEmpty,
                  "no packages found on the participant — DAR upload failed?"
                )

                // Party allocation over the admin gRPC service (rxjava has no party client).
                val hints = List("alice", "bob", "hydrozoa", "adminTT2")
                val parties = CantonParties.allocate("localhost", port, hints)
                assert(parties.keySet == hints.toSet, s"missing parties: $parties")
                assert(parties.values.toSet.size == hints.size, s"party ids not distinct: $parties")
                hints.foreach(h =>
                    assert(
                      parties(h).startsWith(h),
                      s"party id for $h not namespaced: ${parties(h)}"
                    )
                )

                // Submit + typed ACS read: create the registry's TokenRules as admin, read it back.
                val ledger = LedgerClientCanton.connect("localhost", port)
                try
                    val admin = parties("adminTT2")
                    val rules = (for
                        _ <- ledger.createTokenRules(admin)
                        rs <- ledger.activeContractsOf(
                          ContractFilter.of(TokenRules.COMPANION),
                          admin
                        )
                    yield rs).value.unsafeRunSync()
                    assert(rules.exists(_.size == 1), s"expected exactly 1 TokenRules, got: $rules")
                finally ledger.close()
            finally client.close()
        finally container.stop()
