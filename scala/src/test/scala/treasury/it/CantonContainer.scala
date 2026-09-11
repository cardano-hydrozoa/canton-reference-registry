package treasury.it

import java.nio.file.Paths
import java.time.Duration

import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile

/** A single-node Canton (cn-quickstart's Splice image) for integration tests: boots the daemon with
  * our in-memory topology (`app.conf`) and bootstrap (`bootstrap.sc`), uploads the vendored
  * token-standard + TestTokenV2 DARs, and exposes the Ledger API. See `src/test/resources/canton/`.
  */
object CantonContainer:

    /** cn-quickstart pins SPLICE_VERSION=0.6.11; the image tag is that version. */
    private val image = "ghcr.io/digital-asset/decentralized-canton-sync/docker/canton:0.6.11"

    /** Ledger API port from app.conf (participant1.ledger-api.port). */
    val LedgerApiPort = 5011

    /** Vendored DARs live in the sibling daml/ project; tests run with cwd = scala/. */
    private def darsHostPath: String =
        Paths.get(sys.props("user.dir"), "..", "daml", "dars", "vendored").normalize.toString

    def apply(): GenericContainer =
        GenericContainer(
          dockerImage = image,
          exposedPorts = Seq(LedgerApiPort),
          waitStrategy = Wait
              .forLogMessage(".*CANTON-IT BOOTSTRAP OK.*", 1)
              .withStartupTimeout(Duration.ofMinutes(5)),
        ).configure { c =>
            c.withCopyFileToContainer(
              MountableFile.forClasspathResource("canton/app.conf"),
              "/app/app.conf"
            )
            c.withCopyFileToContainer(
              MountableFile.forClasspathResource("canton/bootstrap.sc"),
              "/app/bootstrap.sc"
            )
            c.withCopyFileToContainer(MountableFile.forHostPath(darsHostPath), "/app/dars")
            ()
        }
