package tokenstandard.it

import cats.arrow.FunctionK
import cats.data.EitherT
import cats.effect.IO
import cats.effect.Resource
import cats.effect.testing.scalatest.AsyncIOSpec
import com.dimafeng.testcontainers.GenericContainer
import org.scalatest.funsuite.AsyncFunSuite
import tokenstandard.ledger.CantonM
import tokenstandard.ledger.LedgerClientCanton
import tokenstandard.registry.RegistryApi.Error

/** Shared fixtures for the gated (`CANTON_IT=1`) Canton integration specs: the Docker container as
  * a `Resource`, a raw-gRPC [[LedgerClientCanton]] as a `Resource`, the `CantonM`→`IO` runner, the
  * `IO`→`CantonM` lift the reference registry needs, and the gate. Mixed into an
  * `AsyncFunSuite with AsyncIOSpec`; teardown is by `Resource` bracketing — no `unsafeRunSync`.
  */
trait CantonItFixture:
    self: AsyncFunSuite & AsyncIOSpec =>

    protected val cantonContainer: Resource[IO, GenericContainer] =
        Resource.make(IO.blocking { val c = CantonContainer(); c.start(); c })(c =>
            IO.blocking(c.stop())
        )

    protected def ledgerClient(host: String, port: Int): Resource[IO, LedgerClientCanton] =
        Resource.make(IO.blocking(LedgerClientCanton.connect(host, port)))(l =>
            IO.blocking(l.close())
        )

    protected def portOf(container: GenericContainer): IO[Int] =
        IO.blocking(container.mappedPort(CantonContainer.LedgerApiPort))

    protected def run[A](c: CantonM[A]): IO[A] = c.value.flatMap(IO.fromEither)

    /** Lift the IO-based reference registry into the flow's `CantonM` (RegistryApi errors pass
      * through; anything else is wrapped as `Unexpected`).
      */
    protected val liftIO: FunctionK[IO, CantonM] = new FunctionK[IO, CantonM]:
        def apply[A](fa: IO[A]): CantonM[A] =
            EitherT(fa.attempt.map(_.left.map {
                case e: Error => e
                case t        => Error.Unexpected(Option(t.getMessage).getOrElse(t.toString))
            }))

    /** Cancel the test unless `CANTON_IT=1` — the gate keeping the container out of `sbt test`. */
    protected def requireCantonIt(): Unit =
        val _ = assume(
          sys.env.get("CANTON_IT").contains("1"),
          "set CANTON_IT=1 to run Canton integration tests",
        )
