# canton-reference-registry

A Scala 3 implementation of **CIP-0112** (the Canton Network token standard): the two
client/provider seams a token-standard consumer needs — the off-chain **registry API** and the
on-ledger **ledger client** — together with reference implementations, a fast in-process ledger,
and a conformance testkit. Factored out of a Hydrozoa-on-Canton PoC so the token-standard work can
be built and tested on its own.

> ### ⚠️ Status: alpha, agent-assisted, needs human review
>
> This is an **agent-assisted proof of concept**. It is **alpha-level quality** and **in need of
> substantial human review** before it is relied on for anything. Large parts were written by an AI
> coding agent; the design, the reference implementations, and especially any security- or
> correctness-sensitive behavior have **not** been through a thorough human audit. Treat the APIs,
> the conformance claims, and the wire encodings as provisional and subject to change.

## What this is

CIP-0112 defines the on-chain and off-chain components of an asset registry:

- **On-chain** components are Daml *interfaces* compiled to DARs, then code-generated to Java via the
  official Daml Java codegen. The generated types are authoritative; we use them directly, adding
  opaque wrappers only for off-chain identifiers (`PartyId`, `Cid`, `Blob`, `TemplateId`,
  `SynchronizerId`) plus smart constructors.
- **Off-chain** components are OpenAPI specs, code-generated to Scala model/DTO classes (circe
  codecs) via `openapi-generator-cli`.

The only official implementations we found are the Amulet (Canton Coin) registry and a
`TestTokenV2_Registry` written in Daml Script (which runs in the in-memory Script universe and
exposes no HTTP). A real app needs to exercise the HTTP APIs and model multi-asset, multi-registry,
multi-synchronizer workflows — so this repo provides:

- **Scala 3 traits** for the two seams: `RegistryApi[F]` (the off-chain registry surface a provider
  implements and a consumer programs against) and `LedgerClient[F]` (submit codegen commands, read
  the ACS/balances).
- **Reference implementations** of both, ported from `TestTokenV2_Registry`: a pure,
  `F[_]`-parametric context-assembly core behind an `AcsSource[F]` seam, an http4s server/client over
  the full V2 off-chain surface (all 13 endpoints of the four vendored specs), an in-process
  Daml-interpreter ledger, and a live-Canton gRPC ledger client.
- A **conformance testkit** — `F[_]`-parametric unit + property tests every conformant implementation
  must pass, with live-Canton acceptance tests behind a gate.

Conformance is targeted at **CIP-0112 and the Daml Script `TestTokenV2_Registry`**, not at splice
itself. Some types/traits are duplicated from splice because it does not ship them as library
artifacts; if splice ever publishes authoritative components, much of this repo becomes redundant.

See the PR that introduced the Scala port (#2) for the full design write-up and implementation notes.

## Modules (`scala/`)

| sbt id | artifact | provides |
|---|---|---|
| `api` | `registry-api` | the `RegistryApi`/`LedgerClient` traits, `PartyId`, and the generated Daml + OpenAPI types |
| `impl` | `registry-impl` | reference impls: `LocalRegistryApi`/`Assemble`/`AcsSource`, the http4s server + client, and the live-Canton adapters `LedgerClientCanton` / `AcsSourceCanton` |
| `engine` | `registry-engine` | the fast reference tier: the real Daml interpreter in-process (`EngineLedger`/`EngineRegistry`, bundled DARs, no container) |
| `testkit` | `registry-testkit` | the `Cip0112Conformance` property suite + `MockAcsSource` doubles |
| `testkit-it` | `registry-testkit-it` | the live-Canton harness (boot a container, allocate parties, mint) |

The graph is linear: `impl`/`engine`/`testkit(-it)` all `dependsOn(api)`; `engine`/`testkit-it`
`dependsOn(impl)`. Depending on `api` (traits) or `impl` (HTTP registry) alone stays light; the
engine and its DARs, and testcontainers, are isolated in their own modules.

## Using it (JitPack)

The `scala/` modules are published via [JitPack](https://jitpack.io). Add the resolver and pull a
module by its artifact id with `%%`:

```scala
resolvers += "jitpack" at "https://jitpack.io"

// the fast in-process engine tier (pulls registry-impl + registry-api transitively):
libraryDependencies +=
  "com.github.cardano-hydrozoa.canton-reference-registry" %% "registry-engine" % "0.1.3"
```

Program your flows against the `api` traits, run them fast against the `engine` tier, and again
against a real registry over HTTP for integration. Full consumer guide, including running against
live Canton: [`scala/CONSUMING.md`](scala/CONSUMING.md).

**Cutting a release:** bump `version` in `scala/build.sbt`, commit, tag `vX.Y.Z`, push the tag;
JitPack builds it on first request. JitPack has no nix/DPM, so the build fetches its own sbt 2
launcher (`jitpack.yml`), resolves the Daml codegen from Maven Central, and reads the committed
vendored DARs/specs — see below.

## Repository layout

One top-level `flake.nix` (flake-parts) owns both dev shells; each subproject contributes its own
shell from its own `build.nix`, so there is a single `nixpkgs` pin and `flake.lock`. Each
subdirectory keeps a directory-local `.envrc` (direnv) that selects its shell:

| Directory | Shell | Toolchain | Enter with |
| --------- | ----- | --------- | ---------- |
| [`scala/`](scala/) | `.#scala` | JDK, sbt 2, scala-cli, scalafix/scalafmt | `cd scala` |
| [`daml/`](daml/) | `.#daml` | Daml SDK 3.5.2 (DPM) + Canton 3.5.15 | `cd daml` |

With [direnv](https://direnv.net/), `cd scala` or `cd daml` activates that directory's shell (each
`.envrc` runs `use flake ..#scala` / `..#daml`). Without direnv, `nix develop .#scala` / `.#daml`
from the repo root.

- **`scala/`** — the CIP-0112 library (above). See [`scala/CONSUMING.md`](scala/CONSUMING.md).
- **`daml/`** — the Daml + Canton sandbox: the token-standard packages, the vendored Token Standard
  V2 DARs, a Canton smoke topology, and `justfile` recipes. See [`daml/README.md`](daml/README.md).

## Vendored splice inputs

The build consumes 21 splice Token-Standard DARs (`daml/dars/vendored/*.dar`) and four registry
OpenAPI specs (`scala/registry-openapi/*.yaml`), built from a pinned nix derivation
(`nix build .#vendored-splice`, [canton-network/splice](https://github.com/canton-network/splice) tag
`0.6.11`). These files are **committed** (real files) so JitPack and fresh clones build without nix;
the derivation remains the source of truth and refresh mechanism (`daml/nix/link-vendored.sh` only
re-links a path when a committed copy is absent). Rebuild is byte-for-byte identical to the committed
set and needs no network. Only the test-only `external-test-sources` tree stays generated/gitignored.
See the vendored READMEs for provenance and the reproducibility subtleties.
