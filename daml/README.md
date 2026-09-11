# daml-scratch

A standalone sandbox for learning [Daml](https://docs.daml.com/). It gives you a
reproducible Daml + Canton toolchain via Nix and a minimal package to build on —
factored out of a Hydrozoa-on-Canton PoC so you can experiment without dragging
the rest of that project along.

## Toolchain

The Nix flake pins:

- **Daml SDK 3.5.2** via **DPM** (the Daml Package Manager — the 3.x replacement
  for the legacy `daml` assistant), installed from `get.digitalasset.com` by a
  plain nix derivation (`nix/dpm.nix`). `dpm` provides the compiler and the IDE
  language server. This mirrors the official [cn-quickstart](https://github.com/digital-asset/cn-quickstart)
  toolchain setup (`nix/{dpm,overlays,shell}.nix`).
- **Canton 3.5.15** open-source runtime (sequencer + mediator + participant +
  console), fetched from the Digital Asset release (`nix/canton.nix`).
- **VS Code** (`code`), for the Daml extension (see [IDE](#ide--vs-code-daml-studio)).

Compiler and runtime versions differ deliberately: the SDK targets Daml-LF
**2.1**, which the Canton 3.5.15 runtime accepts (Canton checks LF compatibility,
not an exact SDK match). Hence `daml.yaml` builds with `--target=2.1`.

> Earlier revisions used [obsidiansystems/nix-daml-sdk](https://github.com/obsidiansystems/nix-daml-sdk),
> which tops out at SDK 3.4.11. We moved to DPM to get **3.5.2**, which is needed
> for the Canton Network **Token Standard V2 (CIP-0112)** packages vendored under
> `dars/vendored/` (see that dir's README) and wired into `daml.yaml` as
> `data-dependencies`.

## Getting a shell

The dev shells live in one top-level flake at the repo root (see the top-level
`README.md`); this project's shell is `.#daml`, contributed by `build.nix`.

```bash
cd daml-scratch
nix develop .#daml   # puts dpm, canton, just on PATH
```

Or, with [direnv](https://direnv.net/) installed, `cd daml` and `direnv allow`;
`.envrc` (`use flake ..#daml`) loads the shell automatically.

## Layout

| Path | Role |
|---|---|
| `daml.yaml` | package manifest (SDK version, deps, LF target) |
| `daml/Main.daml` | a minimal `Asset` template + a Daml Script test — the starting point |
| `daml/TokenStandardScratch.daml` | example exercising the Token Standard V2 (CIP-0112) API |
| `dars/vendored/` | vendored token-standard DARs (V1 + V2) + provenance README |
| `canton/topology.conf` | local single-process Canton (1 synchronizer + 1 participant, in-memory) |
| `canton/smoke.canton` | boot Canton, connect, upload the DAR |
| `justfile` | task recipes |
| `nix/` | `dpm.nix`, `canton.nix`, `overlays.nix`, `shell.nix` (cn-quickstart style) |
| `build.nix` | flake-parts module: exposes the `.#daml` dev shell + `.#canton` package (the pin lives in the root `flake.nix` / `flake.lock`) |

## The core loop

Daml Script runs in an in-memory ledger — no Canton needed — so the tightest
feedback loop is just build + test:

```bash
just build   # dpm build → .daml/dist/scratch-0.1.0.dar
just test    # dpm test — runs the Script(s) in daml/
```

## IDE — VS Code (Daml Studio)

VS Code (`code`) is provided by the flake, so no separate install is needed. The
**Daml** extension gives you syntax highlighting, type-on-hover, and the inline
**Script results** lens — click "Script results" above a `Script` value to run it
and inspect the ledger. `.vscode/extensions.json` recommends it (and the direnv
extension); accept the prompt, or install via Extensions → `@recommended`.

The extension is configured to run its language server via **DPM** (SDK 3.5.2) —
`.vscode/settings.json` sets `"daml.useDPMWhenAvailable": true` — so type-on-hover
and autocomplete on the token-standard V2 modules work against the same compiler
that `dpm build` uses.

The one thing that matters: **VS Code must inherit the flake's devShell
environment**, or it won't find `dpm`/`canton`/`git`. Two idiomatic ways:

```bash
just code            # = `code .` from inside the dev shell — inherits its PATH
# or
nix develop -c code .   # launch straight from the flake, from anywhere
```

Or, with [direnv](https://direnv.net/), `direnv allow` once and install the
**direnv** VS Code extension (`mkhl.direnv`); it loads `.envrc` (`use flake ..#daml`)
into the editor automatically, keeping the environment fresh as the flake
changes. This is the [recommended Nix + VS Code setup](https://nixos.asia/en/vscode).

> `dpm studio` also installs the SDK's Daml extension into VS Code and launches
> it, but `just code` / direnv are the robust path since they guarantee the
> editor inherits the full devShell PATH.
>
> On a headless machine there's no GUI to open; the `just build` / `just test`
> loop needs no IDE.

## Running on a real ledger (Canton)

When you want an actual running ledger (persistent state, the Ledger API, party
management, multiple participants):

```bash
just smoke     # boot Canton, bootstrap the synchronizer, connect, upload the DAR
just console   # interactive Canton console on the same topology (Ctrl-D to exit)
```

Inside the console you have `participant1`, `sequencer1`, `mediator1`, etc. as
handles. Typical first steps:

```scala
nodes.local.start()
bootstrap.synchronizer_local()
participant1.synchronizers.connect_local(sequencer1, alias = "da")
participant1.dars.upload(".daml/dist/scratch-0.1.0.dar")
val alice = participant1.parties.enable("Alice")
```

## Where to go next

- Edit `daml/Main.daml` (or add modules under `daml/`) and re-run `just test`.
- Add participants to `canton/topology.conf` for multi-node / multi-party
  scenarios.
- Daml docs: <https://docs.daml.com/> · Canton console:
  <https://docs.daml.com/canton/usermanual/console.html>
