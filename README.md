# daml-scratch

A standalone sandbox for learning [Daml](https://docs.daml.com/). It gives you a
reproducible Daml + Canton toolchain via Nix and a minimal package to build on —
factored out of a Hydrozoa-on-Canton PoC so you can experiment without dragging
the rest of that project along.

## Toolchain

The Nix flake pins:

- **Daml SDK 3.4.11** (`daml` assistant/compiler + `dpm`), via
  [obsidiansystems/nix-daml-sdk](https://github.com/obsidiansystems/nix-daml-sdk).
- **Canton 3.5.15** open-source runtime (sequencer + mediator + participant +
  console), fetched from the Digital Asset release.
- **VS Code** (`code`), so `daml studio` can install the Daml Studio extension.

The compiler and runtime versions differ deliberately: SDK 3.4.11 targets
Daml-LF **2.1**, which the Canton 3.5.15 runtime accepts (Canton checks LF
compatibility, not an exact SDK match). Hence `daml.yaml` builds with
`--target=2.1`.

## Getting a shell

```bash
cd daml-scratch
nix develop          # puts daml, dpm, canton, just on PATH
```

Or, with [direnv](https://direnv.net/) installed, `direnv allow` and the `.envrc`
loads the flake automatically on `cd`.

## Layout

| Path | Role |
|---|---|
| `daml.yaml` | package manifest (SDK version, deps, LF target) |
| `daml/Main.daml` | a minimal `Asset` template + a Daml Script test — the starting point |
| `canton/topology.conf` | local single-process Canton (1 synchronizer + 1 participant, in-memory) |
| `canton/smoke.canton` | boot Canton, connect, upload the DAR |
| `justfile` | task recipes |
| `flake.nix` / `flake.lock` | the pinned toolchain |

## The core loop

Daml Script runs in an in-memory ledger — no Canton needed — so the tightest
feedback loop is just build + test:

```bash
just build   # daml build → .daml/dist/scratch-0.1.0.dar
just test    # daml test — runs the Script(s) in daml/
```

## IDE — Daml Studio (VS Code)

From inside the dev shell:

```bash
daml studio
```

This launches VS Code and, on first run, installs the **Daml Studio** extension
that ships with the SDK (so its version matches the compiler). The extension
gives you syntax highlighting, type-on-hover, jump-to-definition, and the
**Script results** code lens — click "Script results" above a `Script` value to
run it inline and inspect the resulting ledger. `code` is provided by the flake,
so no separate VS Code install is needed.

> On a headless machine there's no GUI to open; use `daml studio` from a desktop
> checkout. The core `just build` / `just test` loop needs no IDE.

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
