import scala.sys.process.Process

ThisBuild / scalaVersion := "3.3.7" // LTS; matches the hydrozoa repo
ThisBuild / organization := "tokenstandard"

// scalafix reads SemanticDB emitted by the Scala 3 compiler; -Wunused:all backs
// OrganizeImports' removeUnused.
ThisBuild / semanticdbEnabled := true

val catsCoreV = "2.13.0"
val catsEffectV = "3.6.3"
// Runtime for the Daml Java codegen output (com.daml.ledger.javaapi.data.*). Daml publishes
// immutable "snapshot" versions to Maven Central; this is the latest 3.4 line, compatible with
// the codegen-java 3.4.x component DPM ships. Bump alongside the DARs / codegen.
val bindingsJavaV = "3.4.0-snapshot.20250626.13943.0.v7067a3a5"
// gRPC Ledger API client (DamlLedgerClient) for the Java bindings — used by LedgerClientCanton.
val bindingsRxJavaV = bindingsJavaV
val scalatestV = "3.2.19"
val testcontainersV = "0.43.0"
// HTTP layer for the registry service (server) + the versions the openapi-generator output targets.
val circeV = "0.14.9"
val http4sV = "0.23.26"
// openapi-generator-cli: generates the CIP-0112 registry wire types (DTOs) from the vendored specs.
val openapiGenV = "7.25.0"
val scalacheckV = "1.18.0"

// Tool-only Ivy configuration so the openapi-generator CLI (a fat jar with a large dep tree) is
// resolved for the build's source generator but never leaks onto the compile/runtime classpath.
lazy val OpenApiCodegen = config("openapiCodegen").hide

// Token-standard V2 DARs the codegen consumes (live in the sibling daml/ project). Order matters
// only for readability; the codegen dedups shared daml-prim/stdlib modules across them.
lazy val tokenStandardDars = settingKey[Seq[String]]("Vendored DAR filenames fed to the codegen")

lazy val registryOpenApiSpecs =
    settingKey[Seq[(String, String)]]("(specFileName, scalaPackage) fed to openapi-generator")

val commonScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Werror", // warnings are build errors; keep our sources clean (CI runs a clean compile)
  // Generated code (Daml codegen, OpenAPI DTOs) under src_managed carries unused imports; don't let
  // -Werror reject machine-generated code. Our own sources stay strict.
  "-Wconf:src=.*src_managed.*:s",
  // InMemoryLedger has total visibility: its actAs/readAs/as/disclosures params are accepted for
  // signature parity with a live client, not enforced. Silence the unused-param warning only there.
  "-Wconf:msg=unused explicit parameter&src=.*InMemoryLedger\\.scala:s",
)

// sbt 2 mis-detects forked ScalaCheck runs and drops all but the first property of a suite; route
// ScalaCheck through the fixed framework (testkit's test.ScalaCheckFrameworkFixed). ScalaTest is
// unaffected. Applied to every project that runs ScalaCheck Properties.
lazy val useFixedScalaCheck: Setting[Seq[TestFramework]] =
    testFrameworks := testFrameworks.value.filterNot(_ == TestFrameworks.ScalaCheck) :+
        new TestFramework("test.ScalaCheckFrameworkFixed")

// Locate the DPM-shipped Java codegen jar. DPM installs it as a component under ~/.dpm rather than
// exposing a `dpm codegen` subcommand, so we resolve the jar directly: $DAML_CODEGEN_JAR wins,
// else the newest version under the DPM component cache. Populated by running `dpm build` (or any
// dpm command) in ../daml at least once. Plain def (not a taskKey) — a File-typed task trips sbt
// 2's output caching.
def resolveDamlCodegenJar(): File =
    sys.env.get("DAML_CODEGEN_JAR").map(file).filter(_.exists).getOrElse {
        val cacheRoot =
            file(sys.props("user.home")) / ".dpm" / "cache" / "components" / "codegen-java"
        val jars = (cacheRoot ** "binary.jar").get().sortBy(_.getParentFile.getName)
        jars.lastOption.getOrElse {
            sys.error(
              "Daml Java codegen jar not found under ~/.dpm/cache/components/codegen-java. " +
                  "Run `dpm build` in ../daml once to install it, or set DAML_CODEGEN_JAR."
            )
        }
    }

// The interface project: the two seams a consumer needs — the CIP-0112 RegistryApi trait
// (tokenstandard.registry) and the LedgerClient trait (tokenstandard.ledger) — plus shared domain
// types (PartyId) and the generated on-ledger Daml bindings and wire DTOs they expose. Hydrozoa's
// src tree depends on this. Runs both code generators.
lazy val api = (project in file("api"))
    .settings(
      name := "registry-api",
      scalacOptions ++= commonScalacOptions,
      ivyConfigurations += OpenApiCodegen,
      libraryDependencies ++= Seq(
        "com.daml" % "bindings-java" % bindingsJavaV,
        "org.typelevel" %% "cats-core" % catsCoreV, // generated DTO codecs (cats.syntax.functor)
        "io.circe" %% "circe-core" % circeV, // generated DTO codecs
        "org.openapitools" % "openapi-generator-cli" % openapiGenV % OpenApiCodegen,
      ),
      resolvers += "jitpack" at "https://jitpack.io",
      registryOpenApiSpecs := Seq(
        "allocation-instruction-v2.yaml" -> "tokenstandard.registry.openapi.allocinstr",
        "allocation-v2.yaml" -> "tokenstandard.registry.openapi.alloc",
        "metadata-v1.yaml" -> "tokenstandard.registry.openapi.metadata",
        "transfer-instruction-v2.yaml" -> "tokenstandard.registry.openapi.transfer",
      ),
      tokenStandardDars := Seq(
        "splice-api-token-metadata-v1-1.0.0.dar",
        "splice-api-token-holding-v2-1.0.0.dar",
        "splice-api-token-allocation-v2-1.0.0.dar",
        "splice-api-token-allocation-instruction-v2-1.0.0.dar",
        "splice-api-token-transfer-instruction-v2-1.0.0.dar",
        "splice-token-standard-utils-2.0.0.dar",
        // The TestTokenV2 registry implementation (TokenRules, AccountConfig, …) — the on-ledger
        // contracts the reference registry assembles choice contexts from and the harness deploys.
        "splice-test-token-v2-1.0.0.dar",
      ),
      Compile / sourceGenerators += Def
          .uncached(Def.task {
              val log = streams.value.log
              val outDir = (Compile / sourceManaged).value / "daml"
              val darDir =
                  (ThisBuild / baseDirectory).value.getParentFile / "daml" / "dars" / "vendored"
              val dars = tokenStandardDars.value.map(darDir / _)
              val jar = resolveDamlCodegenJar()
              val missing = dars.filterNot(_.exists)
              if (missing.nonEmpty)
                  sys.error(s"Missing DARs (build them in ../daml): ${missing.mkString(", ")}")

              // Regenerate only when the DAR set or the codegen jar changes.
              val cached = FileFunction.cached(
                streams.value.cacheDirectory / "daml-codegen",
                FileInfo.hash
              ) { (_: Set[File]) =>
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
          })
          .taskValue,
      // Generate the CIP-0112 registry wire DTOs from the vendored OpenAPI specs. We keep only the
      // `models/` (plain circe case classes); the generator's `apis/` client uses kind-projector `F[*]`
      // syntax that needs -Ykind-projector — the server is hand-written over these DTOs in `impl`.
      Compile / sourceGenerators += Def
          .uncached(Def.task {
              val log = streams.value.log
              val toolCp = update.value.select(configurationFilter(OpenApiCodegen.name))
              val specDir = (ThisBuild / baseDirectory).value / "registry-openapi"
              val outBase = (Compile / sourceManaged).value / "openapi"
              val specs = registryOpenApiSpecs.value.map { case (f, pkg) => (specDir / f, pkg) }
              val missing = specs.map(_._1).filterNot(_.exists)
              if (missing.nonEmpty) sys.error(s"Missing OpenAPI specs: ${missing.mkString(", ")}")

              val cached =
                  FileFunction.cached(
                    streams.value.cacheDirectory / "openapi-codegen",
                    FileInfo.hash
                  ) { (_: Set[File]) =>
                      IO.delete(outBase)
                      specs.flatMap { case (spec, pkg) =>
                          val out = outBase / pkg
                          val args = Seq(
                            "java",
                            "-cp",
                            toolCp.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
                            "org.openapitools.codegen.OpenAPIGenerator",
                            "generate",
                            "-i",
                            spec.getAbsolutePath,
                            "-g",
                            "scala-http4s",
                            "-o",
                            out.getAbsolutePath,
                            "--skip-validate-spec",
                            "--additional-properties",
                            s"packageName=$pkg",
                          )
                          log.info(s"openapi-generator: ${spec.getName} → $pkg")
                          val rc = Process(args).!
                          if (rc != 0)
                              sys.error(s"openapi-generator failed for ${spec.getName} (exit $rc)")
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
                              IO.writeLines(
                                f,
                                IO.readLines(f).filterNot(l => rogueJsonCodecs.exists(l.contains))
                              )
                          }
                          // Keep only the DTOs; drop the generated client (apis/), sbt scaffolding, etc.
                          (out / "src" / "main" / "scala" ** "*.scala")
                              .get()
                              .filter(_.getParentFile.getName == "models")
                      }.toSet
                  }
              cached(specs.map(_._1).toSet).toSeq
          })
          .taskValue,
    )

// The reference implementations: the registry (pure Assemble core + AcsSource/RegistryService/
// LocalRegistryApi + the http4s server/client) and the in-memory ledger (InMemoryLedger). The
// treasury / cross-registry-swap demo flows live in its src/test.
lazy val impl = (project in file("impl"))
    .dependsOn(api)
    .settings(
      name := "registry-impl",
      scalacOptions ++= commonScalacOptions,
      libraryDependencies ++= Seq(
        "org.typelevel" %% "cats-effect" % catsEffectV,
        // gRPC Ledger API client (DamlLedgerClient) — LedgerClientCanton, the live LedgerClient.
        "com.daml" % "bindings-rxjava" % bindingsRxJavaV,
        "io.circe" %% "circe-core" % circeV,
        "io.circe" %% "circe-parser" % circeV,
        "org.http4s" %% "http4s-ember-server" % http4sV,
        "org.http4s" %% "http4s-client" % http4sV,
        "org.http4s" %% "http4s-circe" % http4sV,
        "org.http4s" %% "http4s-dsl" % http4sV,
      ),
    )

// The parametric CIP-0112 conformance suite + reusable test doubles + the ScalaCheck framework fix,
// in src/main so Hydrozoa's test tree can consume them (with api) to conformance-test its own
// registry. Our own specs and the gated Canton integration tests live in this module's src/test —
// they need the doubles + suite (this module) and the reference impl, so keeping them here makes the
// project graph linear (api <- impl <- testkit) instead of a forbidden impl<->testkit cycle.
lazy val testkit = (project in file("testkit"))
    // test->test: the treasury flow demos (TreasuryFlow/CrossRegistrySwapFlow) live in impl's test
    // scope; the specs that exercise them (with testkit's own doubles) are here.
    .dependsOn(api, impl % "compile->compile;test->test")
    .settings(
      name := "registry-testkit",
      scalacOptions ++= commonScalacOptions,
      resolvers += "jitpack" at "https://jitpack.io",
      libraryDependencies ++= Seq(
        "org.typelevel" %% "cats-effect" % catsEffectV, // the doubles (StateT / Applicative)
        "org.scalacheck" %% "scalacheck" % scalacheckV,
        // PropertyM for monadic properties (JitPack; single `%` — the artifact drops the Scala suffix).
        "com.github.cardano-hydrozoa" % "scalacheck-propertym" % "0.1.1",
        "org.scala-sbt" % "test-interface" % "1.0", // ScalaCheckFrameworkFixed uses sbt.testing.*
        "org.scalatest" %% "scalatest" % scalatestV % Test,
        // AsyncIOSpec: run cats-effect IO/Resource directly in ScalaTest (no unsafeRunSync in tests).
        "org.typelevel" %% "cats-effect-testing-scalatest" % "1.6.0" % Test,
        "org.http4s" %% "http4s-ember-client" % http4sV % Test,
        "com.dimafeng" %% "testcontainers-scala-scalatest" % testcontainersV % Test,
        "org.slf4j" % "slf4j-simple" % "2.0.16" % Test,
      ),
      useFixedScalaCheck, // our ScalaCheck suites (FrameworkSmoke, CantonConformanceProperties)
    )

lazy val root = (project in file("."))
    .aggregate(api, impl, testkit)
    .settings(
      name := "daml-scratch-scala",
      publish / skip := true,
    )
