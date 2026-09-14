// Daemon bootstrap. The image's /app/bootstrap-entrypoint.sc does
// `import $file.\`user-bootstrap\`; \`user-bootstrap\`.main()`, so the logic must live in a
// `main()` (a flat script would run at import time and then fail on the missing `main`, taking the
// daemon down with it). Starts the local nodes, bootstraps the synchronizer, connects the
// participant, and uploads every vendored DAR mounted at /app/dars. No sys.exit — the daemon must
// keep serving the Ledger API. The trailing marker is what the testcontainers wait strategy greps.
import java.io.File

def main(): Unit = {
  nodes.local.start()
  bootstrap.synchronizer_local()

  participant1.synchronizers.connect_local(sequencer1, alias = "da")
  utils.retry_until_true { participant1.synchronizers.active("da") }

  Option(new File("/app/dars").listFiles())
    .getOrElse(Array.empty[File])
    .filter(_.getName.endsWith(".dar"))
    .sortBy(_.getName)
    .foreach(f => participant1.dars.upload(f.getAbsolutePath))

  println(">>> CANTON-IT BOOTSTRAP OK")
}
