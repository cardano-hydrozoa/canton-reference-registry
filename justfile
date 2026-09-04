#!/usr/bin/env just --justfile
#
# daml-scratch recipes. Run inside the dev shell (`nix develop`, or direnv via
# `.envrc`), which puts `daml`, `dpm`, `canton`, and `just` on PATH. `just` runs
# these from this directory, so all paths are relative to the repo root.

conf := "canton/topology.conf"

# List recipes.
default:
  @just --list

# Compile the package to a Daml-LF 2.1 DAR (.daml/dist/scratch-0.1.0.dar).
build:
  daml build

# Run the Daml Script tests in daml/ (in-memory script service, no Canton).
test: build
  daml test

# Boot local Canton, bootstrap the synchronizer, connect the participant, upload the DAR.
smoke: build
  canton run canton/smoke.canton -c {{conf}} --no-tty </dev/null

# Open an interactive Canton console on the local topology (Ctrl-D to exit).
console: build
  canton -c {{conf}}

# Remove Daml build artifacts.
clean:
  daml clean
