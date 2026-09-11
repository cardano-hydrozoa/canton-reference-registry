import scala.sys.process.Process

ThisBuild / scalaVersion := "3.3.7" // LTS; matches the hydrozoa repo
ThisBuild / organization := "com.hydrozoa"

// scalafix reads SemanticDB emitted by the Scala 3 compiler; -Wunused:all backs
// OrganizeImports' removeUnused.
ThisBuild / semanticdbEnabled := true

val catsEffectV = "3.6.3"
// Runtime for the Daml Java codegen output (com.daml.ledger.javaapi.data.*). Daml publishes
// immutable "snapshot" versions to Maven Central; this is the latest 3.4 line, compatible with
// the codegen-java 3.4.x component DPM ships. Bump alongside the DARs / codegen.
val bindingsJavaV = "3.4.0-snapshot.20250626.13943.0.v7067a3a5"
// gRPC Ledger API client (DamlLedgerClient) for the Java bindings — Phase 2 Canton integration.
// Same version as bindings-java.
val bindingsRxJavaV = bindingsJavaV
val scalatestV = "3.2.19"
val testcontainersV = "0.43.0"

// Token-standard V2 DARs the codegen consumes (live in the sibling daml/ project). Order matters
// only for readability; the codegen dedups shared daml-prim/stdlib modules across them.
lazy val tokenStandardDars = settingKey[Seq[String]]("Vendored DAR filenames fed to the codegen")

// Locate the DPM-shipped Java codegen jar. DPM installs it as a component under ~/.dpm rather than
// exposing a `dpm codegen` subcommand, so we resolve the jar directly: $DAML_CODEGEN_JAR wins,
// else the newest version under the DPM component cache. Populated by running `dpm build` (or any
// dpm command) in ../daml at least once. Plain def (not a taskKey) — a File-typed task trips sbt
// 2's output caching.
def resolveDamlCodegenJar(): File =
  sys.env.get("DAML_CODEGEN_JAR").map(file).filter(_.exists).getOrElse {
    val cacheRoot = file(sys.props("user.home")) / ".dpm" / "cache" / "components" / "codegen-java"
    val jars = (cacheRoot ** "binary.jar").get().sortBy(_.getParentFile.getName)
    jars.lastOption.getOrElse {
      sys.error(
        "Daml Java codegen jar not found under ~/.dpm/cache/components/codegen-java. " +
          "Run `dpm build` in ../daml once to install it, or set DAML_CODEGEN_JAR."
      )
    }
  }

lazy val root = (project in file("."))
  .settings(
    name := "daml-scratch-scala",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
    ),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectV,
      "com.daml" % "bindings-java" % bindingsJavaV,
      "org.scalatest" %% "scalatest" % scalatestV % Test,
      "com.daml" % "bindings-rxjava" % bindingsRxJavaV % Test,
      "com.dimafeng" %% "testcontainers-scala-scalatest" % testcontainersV % Test,
      "org.slf4j" % "slf4j-simple" % "2.0.16" % Test,
    ),
    tokenStandardDars := Seq(
      "splice-api-token-metadata-v1-1.0.0.dar",
      "splice-api-token-holding-v2-1.0.0.dar",
      "splice-api-token-allocation-v2-1.0.0.dar",
      "splice-api-token-allocation-instruction-v2-1.0.0.dar",
      "splice-api-token-transfer-instruction-v2-1.0.0.dar",
      "splice-token-standard-utils-2.0.0.dar",
      // The TestTokenV2 registry implementation (TokenRules, AccountConfig, …) — the on-ledger
      // contracts RegistryBackendLocal assembles choice contexts from and the harness deploys.
      "splice-test-token-v2-1.0.0.dar",
    ),
    Compile / sourceGenerators += Def.uncached(Def.task {
      val log = streams.value.log
      val outDir = (Compile / sourceManaged).value / "daml"
      val darDir = (ThisBuild / baseDirectory).value.getParentFile / "daml" / "dars" / "vendored"
      val dars = tokenStandardDars.value.map(darDir / _)
      val jar = resolveDamlCodegenJar()
      val missing = dars.filterNot(_.exists)
      if (missing.nonEmpty)
        sys.error(s"Missing DARs (build them in ../daml): ${missing.mkString(", ")}")

      // Regenerate only when the DAR set or the codegen jar changes.
      val cached = FileFunction.cached(streams.value.cacheDirectory / "daml-codegen", FileInfo.hash) {
        (_: Set[File]) =>
          IO.delete(outDir)
          IO.createDirectory(outDir)
          // Single "daml" prefix → clean daml.splice.api.token.* packages, no cross-DAR collisions.
          val args = Seq("java", "-jar", jar.getAbsolutePath, "java") ++
            dars.map(d => s"${d.getAbsolutePath}=daml") ++
            Seq("-o", outDir.getAbsolutePath, "-V", "1")
          log.info(s"Daml Java codegen → $outDir")
          val rc = Process(args).!
          if (rc != 0) sys.error(s"Daml Java codegen failed with exit code $rc")

          // The codegen emits a nested helper class literally named `JsonDecoder$`. Scala's
          // classfile parser reads any `Name$` as the *module class* of `Name`, inventing a cyclic
          // reference (E046) that fails compilation whenever these types are read from bytecode. We
          // don't use JSON-LF (the gRPC Ledger API speaks protobuf), so rename the class — and all
          // references — to a `$`-free identifier before compiling.
          val generated = (outDir ** "*.java").get()
          generated.foreach { f =>
            val content = IO.read(f)
            if (content.contains("JsonDecoder$"))
              IO.write(f, content.replace("JsonDecoder$", "JsonDecoder_"))
          }
          generated.toSet
      }
      cached((dars :+ jar).toSet).toSeq
    }).taskValue,
  )
