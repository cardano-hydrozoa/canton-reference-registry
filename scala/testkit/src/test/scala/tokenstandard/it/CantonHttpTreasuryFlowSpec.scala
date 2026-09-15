package tokenstandard.it

import cats.arrow.FunctionK
import cats.data.EitherT
import cats.effect.Clock
import cats.effect.IO
import cats.effect.Resource
import cats.effect.testing.scalatest.AsyncIOSpec
import com.comcast.ip4s.host
import com.comcast.ip4s.port
import com.dimafeng.testcontainers.GenericContainer
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.scalatest.funsuite.AsyncFunSuite
import tokenstandard.PartyId
import tokenstandard.TreasuryEnv
import tokenstandard.TreasuryFlow
import tokenstandard.it.CantonTestTokenOps.*
import tokenstandard.ledger.CantonM
import tokenstandard.ledger.LedgerClientCanton
import tokenstandard.registry.RegistryApi
import tokenstandard.registry.RegistryApi.Error
import tokenstandard.registry.service.RegistryService
import tokenstandard.registry.service.http.RegistryBackendHttp
import tokenstandard.registry.service.http.RegistryRoutes

/** The deployment shape, end to end: the flow reaches the registry over REAL HTTP —
  * [[RegistryBackendHttp]] (ember client) → [[RegistryRoutes]] (ember server, backed by the
  * reference service over [[AcsSourceCanton]]) → live Canton. Where [[CantonTreasuryFlowSpec]]
  * calls the registry in-process, this proves the wire boundary: the Daml-JSON `choiceArguments`
  * decode, the `choiceContextData` encoding, and the disclosure rendering all round-trip into a
  * context the live ledger ACCEPTS. Gated on `CANTON_IT=1` (see [[CantonSmokeSpec]] for the sandbox
  * run recipe).
  */
class CantonHttpTreasuryFlowSpec extends AsyncFunSuite, AsyncIOSpec:

    private val cantonContainer: Resource[IO, GenericContainer] =
        Resource.make(IO.blocking { val c = CantonContainer(); c.start(); c })(c =>
            IO.blocking(c.stop())
        )

    private def ledgerClient(host: String, port: Int): Resource[IO, LedgerClientCanton] =
        Resource.make(IO.blocking(LedgerClientCanton.connect(host, port)))(l =>
            IO.blocking(l.close())
        )

    /** The registry service served over HTTP on an ephemeral port; yields its base URI. */
    private def registryServer(svc: RegistryService[IO]): Resource[IO, Uri] =
        EmberServerBuilder
            .default[IO]
            .withHost(host"127.0.0.1")
            .withPort(port"0")
            .withHttpApp(RegistryRoutes[IO](svc).routes.orNotFound)
            .build
            .map(_.baseUri)

    private def run[A](c: CantonM[A]): IO[A] = c.value.flatMap(IO.fromEither)

    private val liftIO: FunctionK[IO, CantonM] = new FunctionK[IO, CantonM]:
        def apply[A](fa: IO[A]): CantonM[A] =
            EitherT(fa.attempt.map(_.left.map {
                case e: Error => e
                case t        => Error.Unexpected(Option(t.getMessage).getOrElse(t.toString))
            }))

    test("TreasuryFlow runs green over the HTTP registry boundary on live Canton"):
        assume(
          sys.env.get("CANTON_IT").contains("1"),
          "set CANTON_IT=1 to run Canton integration tests"
        )

        val partyHints = List("adminHF", "providerHF", "aliceHF", "bobHF", "hydrozoaHF")
        val setup =
            for
                container <- cantonContainer
                port <- Resource.eval(
                  IO.blocking(container.mappedPort(CantonContainer.LedgerApiPort))
                )
                parties <- Resource.eval(
                  IO.blocking(CantonParties.allocate("localhost", port, partyHints))
                )
                ledger <- ledgerClient("localhost", port)
                baseUri <- registryServer(
                  RegistryService(AcsSourceCanton(ledger, parties("adminHF")))
                )
                httpClient <- EmberClientBuilder.default[IO].build
            yield (parties, ledger, baseUri, httpClient)

        setup
            .use {
                (
                    parties: Map[String, PartyId],
                    ledger: LedgerClientCanton,
                    baseUri: Uri,
                    httpClient: Client[IO],
                ) =>
                    val env = TreasuryEnv(
                      admin = parties("adminHF"),
                      provider = parties("providerHF"),
                      alice = parties("aliceHF"),
                      bob = parties("bobHF"),
                      hydrozoa = parties("hydrozoaHF"),
                    )
                    val registry =
                        RegistryApi.mapK(RegistryBackendHttp[IO](httpClient, baseUri))(liftIO)
                    val flow = new TreasuryFlow[CantonM](registry, ledger)
                    for
                        now <- Clock[IO].realTimeInstant
                        _ <- run(ledger.createTokenRules(env.admin))
                        _ <- run(ledger.mint(env.admin, env.alice, "X", BigDecimal(1000), now))
                        _ <- run(ledger.mint(env.admin, env.bob, "Y", BigDecimal(1000), now))
                        _ <- run(flow.run(env, now))
                    yield ()
            }
            .asserting(_ => succeed)
