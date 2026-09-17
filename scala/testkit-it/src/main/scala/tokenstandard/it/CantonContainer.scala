package tokenstandard.it

import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile

import java.time.Duration

/** A single-node Canton (cn-quickstart's Splice image) for integration tests: boots the daemon with
  * our in-memory topology (`app.conf`) and bootstrap (`bootstrap.sc`), uploads the vendored
  * token-standard + TestTokenV2 DARs, and exposes the Ledger API. Config and DARs ship as classpath
  * resources of this module (see `src/main/resources/canton/` and the `/dars/` bundle), so a
  * consumer needs nothing on disk.
  */
object CantonContainer:

    /** cn-quickstart pins SPLICE_VERSION=0.6.11; the image tag is that version. */
    private val image = "ghcr.io/digital-asset/decentralized-canton-sync/docker/canton:0.6.11"

    /** Ledger API port from app.conf (participant1.ledger-api.port). */
    val LedgerApiPort = 5011

    /** DAR resource names, from the bundled `/dars/index.txt` (mounted straight from the classpath
      * — testcontainers extracts each resource to a temp file for the container).
      */
    private def darNames: List[String] =
        val is = Option(getClass.getResourceAsStream("/dars/index.txt"))
            .getOrElse(sys.error("bundled /dars/index.txt not found on the classpath"))
        try
            scala.io.Source
                .fromInputStream(is)(scala.io.Codec.UTF8)
                .getLines()
                .filter(_.nonEmpty)
                .toList
        finally is.close()

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
            darNames.foreach { name =>
                c.withCopyFileToContainer(
                  MountableFile.forClasspathResource(s"dars/$name"),
                  s"/app/dars/$name"
                )
            }
            ()
        }
