# daml-scratch

A standalone sandbox for learning [Daml](https://docs.daml.com/) + Canton, and
for porting individual Daml workflows over to Scala. Factored out of a
Hydrozoa-on-Canton PoC so you can experiment without dragging the rest of that
project along.

One top-level `flake.nix` (flake-parts, in the style of
[lambda-buffers](https://github.com/mlabs-haskell/lambda-buffers)) owns both dev
shells: each subproject contributes its own shell from its own `build.nix`
module (`imports = [ ./daml/build.nix ./scala/build.nix ]`), so there is a single
`nixpkgs` pin and a single `flake.lock` for the repo. Each subdirectory keeps a
directory-local `.envrc` (direnv) that selects its shell:

| Directory | Shell | Toolchain | Enter with |
| --------- | ----- | --------- | ---------- |
| [`daml/`](daml/) | `.#daml` | Daml SDK 3.5.2 (DPM) + Canton 3.5.15 | `cd daml` |
| [`scala/`](scala/) | `.#scala` | JDK 25, sbt 2, scala-cli, scalafix/scalafmt | `cd scala` |

## Usage

With [direnv](https://direnv.net/) installed, `cd daml` or `cd scala` activates
that directory's shell automatically — each `.envrc` runs `use flake ..#daml`
(resp. `..#scala`) against the root flake. Without direnv, run `nix develop
.#daml` or `nix develop .#scala` from the repo root.

- **`daml/`** — the Daml + Canton sandbox (package, vendored Token Standard V2
  DARs, Canton smoke topology, and the `justfile` recipes). See
  [`daml/README.md`](daml/README.md) for the full toolchain notes and IDE setup.
- **`scala/`** — Scala dev shell, ported from the hydrozoa repo's flake, for
  porting Daml workflows (e.g. the `TestHydrozoaTreasury` allocation workflow) to
  Scala. No build definition lives here yet.
