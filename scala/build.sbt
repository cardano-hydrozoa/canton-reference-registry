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
// HTTP layer for the registry service (server) + the versions the openapi-generator output targets.
val circeV = "0.14.9"
val http4sV = "0.23.26"
// openapi-generator-cli: generates the CIP-0112 registry wire types (DTOs) from the vendored specs.
val openapiGenV = "7.25.0"

// Tool-only Ivy configuration so the openapi-generator CLI (a fat jar with a large dep tree) is
// resolved for the build's source generator but never leaks onto the compile/runtime classpath.
lazy val OpenApiCodegen = config("openapiCodegen").hide

// Token-standard V2 DARs the codegen consumes (live in the sibling daml/ project). Order matters
// only for readability; the codegen dedups shared daml-prim/stdlib modules across them.
lazy val tokenStandardDars = settingKey[Seq[String]]("Vendored DAR filenames fed to the codegen")

lazy val registryOpenApiSpecs =
  settingKey[Seq[(String, String)]]("(specFileName, scalaPackage) fed to openapi-generator")

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
      // Generated OpenAPI DTOs (under src_managed) carry unused imports; don't let -Werror reject
      // machine-generated code. Our own sources stay strict.
      "-Wconf:src=.*src_managed.*:s",
    ),
    ivyConfigurations += OpenApiCodegen,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectV,
      "com.daml" % "bindings-java" % bindingsJavaV,
      "io.circe" %% "circe-core" % circeV,
      "io.circe" %% "circe-parser" % circeV,
      "org.http4s" %% "http4s-ember-server" % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV,
      "org.http4s" %% "http4s-dsl" % http4sV,
      "org.openapitools" % "openapi-generator-cli" % openapiGenV % OpenApiCodegen,
      "org.scalatest" %% "scalatest" % scalatestV % Test,
      // AsyncIOSpec: run cats-effect IO/Resource directly in ScalaTest (no unsafeRunSync in tests).
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.6.0" % Test,
      "com.daml" % "bindings-rxjava" % bindingsRxJavaV % Test,
      "org.http4s" %% "http4s-ember-client" % http4sV % Test,
      "com.dimafeng" %% "testcontainers-scala-scalatest" % testcontainersV % Test,
      "org.slf4j" % "slf4j-simple" % "2.0.16" % Test,
      // CIP-0112 conformance suite: ScalaCheck (pinned to match scalacheck-propertym) + PropertyM for
      // monadic properties (JitPack; single `%` — the artifact drops the Scala `_3` suffix).
      "org.scalacheck" %% "scalacheck" % "1.18.0" % Test,
      "com.github.cardano-hydrozoa" % "scalacheck-propertym" % "0.1.1" % Test,
    ),
    resolvers += "jitpack" at "https://jitpack.io",
    // sbt 2 mis-detects forked ScalaCheck runs and drops all but the first property of a suite; route
    // ScalaCheck through the fixed framework (see test/ScalaCheckFrameworkFixed). ScalaTest unaffected.
    testFrameworks := testFrameworks.value.filterNot(_ == TestFrameworks.ScalaCheck) :+
      new TestFramework("test.ScalaCheckFrameworkFixed"),
    // (specFile in registry-openapi/, generated Scala package) — the CIP-0112 registry API is split
    // per interface, each spec self-contained. Scoped to the two factory specs the treasury/swap flow
    // needs; transfer-instruction + metadata are added when a flow needs them.
    registryOpenApiSpecs := Seq(
      "allocation-instruction-v2.yaml" -> "treasury.registry.openapi.allocinstr",
      "allocation-v2.yaml" -> "treasury.registry.openapi.alloc",
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
    // Generate the CIP-0112 registry wire DTOs from the vendored OpenAPI specs. We keep only the
    // `models/` (plain circe case classes); the generator's `apis/` client uses kind-projector `F[*]`
    // syntax that needs -Ykind-projector, and is a step-7 concern — the server is hand-written over
    // these DTOs. Regenerates only when a spec or the generator jar changes (mirrors the Daml codegen).
    Compile / sourceGenerators += Def.uncached(Def.task {
      val log = streams.value.log
      val toolCp = update.value.select(configurationFilter(OpenApiCodegen.name))
      val specDir = baseDirectory.value / "registry-openapi"
      val outBase = (Compile / sourceManaged).value / "openapi"
      val specs = registryOpenApiSpecs.value.map { case (f, pkg) => (specDir / f, pkg) }
      val missing = specs.map(_._1).filterNot(_.exists)
      if (missing.nonEmpty) sys.error(s"Missing OpenAPI specs: ${missing.mkString(", ")}")

      val cached =
        FileFunction.cached(streams.value.cacheDirectory / "openapi-codegen", FileInfo.hash) {
          (_: Set[File]) =>
            IO.delete(outBase)
            specs.flatMap { case (spec, pkg) =>
              val out = outBase / pkg
              val args = Seq(
                "java",
                "-cp",
                toolCp.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
                "org.openapitools.codegen.OpenAPIGenerator",
                "generate",
                "-i", spec.getAbsolutePath,
                "-g", "scala-http4s",
                "-o", out.getAbsolutePath,
                "--skip-validate-spec",
                "--additional-properties", s"packageName=$pkg",
              )
              log.info(s"openapi-generator: ${spec.getName} → $pkg")
              val rc = Process(args).!
              if (rc != 0) sys.error(s"openapi-generator failed for ${spec.getName} (exit $rc)")
              // The scala-http4s template emits rogue Json codecs in the package object that encode a
              // free-form `type: object` field (choiceArguments/choiceContextData) as an *escaped
              // string* instead of embedded JSON — wrong for the token standard. Strip them so circe's
              // identity Encoder/Decoder[Json] is used (mirrors the JsonDecoder$ fix above).
              val rogueJsonCodecs = Seq(
                "given decodeJson: Decoder[Json]",
                "Decoder.decodeString.map(str => Json.fromString(str))",
                "given encodeJson: Encoder[Json]",
                "Encoder.encodeString.contramap[Json](_.toString)",
              )
              (out ** "package.scala").get().foreach { f =>
                IO.writeLines(f, IO.readLines(f).filterNot(l => rogueJsonCodecs.exists(l.contains)))
              }
              // Keep only the DTOs; drop the generated client (apis/), sbt scaffolding, etc.
              (out / "src" / "main" / "scala" ** "*.scala").get()
                .filter(_.getParentFile.getName == "models")
            }.toSet
        }
      cached(specs.map(_._1).toSet).toSeq
    }).taskValue,
  )
