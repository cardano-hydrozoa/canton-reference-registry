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
- **VS Code** (`code`), for the Daml extension (see [IDE](#ide--vs-code-daml-studio)).

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

## IDE — VS Code (Daml Studio)

VS Code (`code`) is provided by the flake, so no separate install is needed. The
**Daml** extension gives you syntax highlighting, type-on-hover, and the inline
**Script results** lens — click "Script results" above a `Script` value to run it
and inspect the ledger. `.vscode/extensions.json` recommends it (and the direnv
extension); accept the prompt, or install via Extensions → `@recommended`.

The one thing that matters: **VS Code must inherit the flake's devShell
environment**, or it won't find `daml`/`canton`/`git`. Two idiomatic ways:

```bash
just code            # = `code .` from inside the dev shell — inherits its PATH
# or
nix develop -c code .   # launch straight from the flake, from anywhere
```

Or, with [direnv](https://direnv.net/), `direnv allow` once and install the
**direnv** VS Code extension (`mkhl.direnv`); it loads `.envrc` (`use flake .`)
into the editor automatically, keeping the environment fresh as the flake
changes. This is the [recommended Nix + VS Code setup](https://nixos.asia/en/vscode).

> **Don't use `daml studio` to launch the editor.** nix-daml-sdk's `daml`
> launcher resets `PATH`, so `daml studio` opens VS Code with no `git`, `direnv`,
> or `daml` on PATH. This flake patches the launcher to append the caller's PATH,
> so `daml studio` *from inside the dev shell* now works — but `just code` /
> direnv are the robust path.
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
