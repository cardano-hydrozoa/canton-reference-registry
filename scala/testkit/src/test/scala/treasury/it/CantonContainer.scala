package treasury.it

import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import scala.jdk.CollectionConverters.*

/** A single-node Canton (cn-quickstart's Splice image) for integration tests: boots the daemon with
  * our in-memory topology (`app.conf`) and bootstrap (`bootstrap.sc`), uploads the vendored
  * token-standard + TestTokenV2 DARs, and exposes the Ledger API. See `src/test/resources/canton/`.
  */
object CantonContainer:

    /** cn-quickstart pins SPLICE_VERSION=0.6.11; the image tag is that version. */
    private val image = "ghcr.io/digital-asset/decentralized-canton-sync/docker/canton:0.6.11"

    /** Ledger API port from app.conf (participant1.ledger-api.port). */
    val LedgerApiPort = 5011

    /** Vendored DARs live in the sibling daml/ project; tests run with cwd = scala/. Each entry is
      * a symlink into the nix store (daml/nix/link-vendored.sh) — resolve to the real path when
      * mounting: testcontainers' MountableFile can't tar a symlink entry, and /nix/store isn't
      * visible inside the container.
      */
    private def vendoredDars: List[Path] =
        val dir = Paths.get(sys.props("user.dir"), "..", "daml", "dars", "vendored").normalize
        val stream = Files.list(dir)
        val dars =
            try stream.iterator.asScala.filter(_.toString.endsWith(".dar")).toList.sorted
            finally stream.close()
        require(dars.nonEmpty, s"no vendored DARs in $dir — enter the devShell to link them")
        dars

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
            vendoredDars.foreach { p =>
                c.withCopyFileToContainer(
                  MountableFile.forHostPath(p.toRealPath()),
                  s"/app/dars/${p.getFileName}"
                )
            }
            ()
        }
