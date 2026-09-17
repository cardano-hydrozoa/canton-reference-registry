# Consuming this library (Hydrozoa handoff)

This is the Scala CIP-0112 token-standard library: the client/registry **traits** a
consumer programs against, plus **reference implementations** to test against without
hand-rolling a ledger. The intended consumer is Hydrozoa — write your flows against the
traits, run them fast against the in-process engine tier, and again against a real
registry over HTTP for integration.

## Modules

| sbt id (`ProjectRef`) | artifact | provides | heavy deps |
|---|---|---|---|
| `api` | `registry-api` | the traits + generated types: `RegistryApi`, `LedgerClient`, `Submission`, the Daml codegen (`daml.splice.api.token.*`) and OpenAPI DTOs | bindings-java (gRPC) |
| `impl` | `registry-impl` | reference impls: pure `Assemble`/`RegistryService`, `LocalRegistryApi`, the http4s server (`RegistryRoutes`) + client (`RegistryBackendHttp`), and the live-Canton adapters `LedgerClientCanton` + `AcsSourceCanton` | http4s, circe |
| `engine` | `registry-engine` | **the fast reference tier**: `EngineLedger` (a `LedgerClient` backed by the real Daml interpreter, in-process, no container) + `EngineRegistry` + `DamlEngine` | **daml-lf-engine 3.5.15** + the ~21 vendored DARs (bundled as resources) |
| `testkit-it` | `registry-testkit-it` | **the live-Canton harness**: `CantonContainer` (boots the node, DARs + config bundled), `CantonParties` (allocate), `CantonTestTokenOps` (deploy TestTokenV2 + mint) | testcontainers + the bundled DARs |
| `testkit` | `registry-testkit` | reusable test doubles: `MockAcsSource`, the `Cip0112Conformance` property suite | scalacheck |

`impl`/`engine`/`testkit-it` all `dependsOn(api)` (`engine`/`testkit-it dependsOn impl`).
Each heavy concern is isolated in its own module: the engine + DARs in `engine`,
testcontainers + the container's DARs in `testkit-it`. Depending on `impl` (HTTP registry)
or `api` (traits) alone stays light.

## How Hydrozoa depends on it

Today: an sbt **source dependency** via `ProjectRef` on this branch
(`cardano-hydrozoa/canton-reference-registry`, `peter/scala-phase2-canton`). In your
`build.sbt`:

```scala
lazy val cantonReg = file("path/to/canton-reference-registry/scala") // a checkout of this repo

// production code programs against the traits:
lazy val myApp = project.dependsOn(ProjectRef(cantonReg, "api"))

// fast tests against the engine reference tier:
lazy val myTests = project.dependsOn(ProjectRef(cantonReg, "engine"))

// integration tests against the HTTP reference registry over live Canton:
//   .dependsOn(ProjectRef(cantonReg, "impl"), ProjectRef(cantonReg, "testkitIt"))
```

(sbt project ids are the `lazy val` names — `api`, `impl`, `engine`, `testkitIt` — not the
`registry-*` artifact names.)

(Later, once open-sourced, the same modules can be pulled from JitPack as
`tokenstandard:registry-{api,impl,engine}` coordinates instead of `ProjectRef`.)

> **Your build must be sbt 2** (this repo builds with sbt 2.0.1 and uses sbt-2-only build
> APIs, so an sbt-1 consumer can't load it via `ProjectRef`). Hydrozoa is already on sbt 2.
> Verified: a standalone sbt-2 build `ProjectRef`-ing `engine` loads the bundled DARs and
> runs the engine ledger end to end.
>
> **Prerequisite for a source/ProjectRef build:** the vendored splice DARs must exist on
> disk in this repo — they are nix-generated and git-ignored. Run the daml devShell /
> `just vendor` in this repo once so `daml/dars/vendored/` is populated; the `engine`
> module's resource step reads them from there at build time. (A *published* jar carries
> the DARs inside it, so that prerequisite disappears on the JitPack path.)

## Fast tests — run your flow against the engine ledger

`EngineLedger`/`EngineRegistry` run the **real** TestTokenV2 Daml code in-process (real
interpretation + authorization), yet stay a pure `StateT` over `Either` — no IO runtime,
no Docker. Effect type: `EngineM`.

```scala
import tokenstandard.engine.*
import tokenstandard.PartyId

val engine   = DamlEngine.load()                 // decodes the bundled DARs once
val ledger   = new EngineLedger(engine)
val registry = EngineRegistry(engine, admin = PartyId("reg"), instruments = List("X"))

// your flow is parametric in F; here F = EngineM
val flow = new MyFlow(registry, ledger)

val program = for
  _ <- ledger.createTokenRules(PartyId("reg"))                          // deploy the registry
  _ <- ledger.seedHolding(PartyId("reg"), PartyId("alice"), xId, 1000)  // mint alice her holdings
  _ <- flow.run(...)
yield ()

val result = program.run(EngineStore.empty)   // Either[Error, (EngineStore, Unit)]
```

Worked examples: `testkit/src/test/scala/tokenstandard/{TreasuryFlowSpec,CrossRegistrySwapSpec}.scala`.

## Integration tests — your flow against the HTTP reference registry over live Canton

The reference registry served over HTTP + the Canton adapters are all in `impl` (light):
`RegistryService` over `AcsSourceCanton(ledgerClientCanton, admin)`, served by
`RegistryRoutes` (http4s), consumed by `RegistryBackendHttp`, over `LedgerClientCanton`
(raw gRPC). Effect type on the live path: `CantonM = EitherT[IO, Error, *]`.

The Canton harness — `CantonContainer` (boots the node; DARs + config bundled as
resources), `CantonParties` (allocate parties), `CantonTestTokenOps` (`createTokenRules`
+ `mint` for TestTokenV2) — is in `testkit-it` (src/main), so it is consumable: your
integration test boots a container and mints holdings with no repo files on disk. Sketch:

```scala
import tokenstandard.it.*
import tokenstandard.it.CantonTestTokenOps.*
import tokenstandard.ledger.LedgerClientCanton
import tokenstandard.registry.service.*

val container = CantonContainer()                      // testcontainers Resource in the specs
val port      = container.mappedPort(CantonContainer.LedgerApiPort)
val parties   = CantonParties.allocate("localhost", port, List("admin", "alice"))
val ledger    = LedgerClientCanton.connect("localhost", port)
// serve the reference registry over HTTP (RegistryRoutes) backed by AcsSourceCanton,
// consume it with RegistryBackendHttp, run your flow — see the worked example below.
```

Worked example (the exact end-to-end wiring): `testkit/src/test/scala/tokenstandard/it/CantonHttpTreasuryFlowSpec.scala`, with the reusable fixtures in `CantonItFixture.scala`.

## Gotchas / notes

- **Engine version is pinned to the Canton *container* (3.5.15), not the bindings pin
  (3.4).** The engine must match the LF the vendored splice DARs were compiled against; a
  3.4 engine rejects their builtins. Both coexist by design — they only meet at the
  wire/LF-value boundary, which is stable across the two.
- The engine emits *local* contract ids; `DamlEngine.submit` suffixes each transaction
  before storing (handled internally — you don't see it). Disclosures are no-ops in the
  single-store engine (every contract resolves), so `EngineLedger` ignores the disclosure
  list — but the choice-context *values* your registry produces are validated by real Daml
  execution, so a wrong `Assemble` fails your test.
- Scala 3.3.7, matching the Hydrozoa repo.
