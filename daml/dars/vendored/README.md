# Vendored DARs — Canton Network Token Standard (V1 + V2 / CIP-0112)

These `.dar` files are the `data-dependencies` of the daml/ and scala/ builds (see
`../../daml.yaml`), following the CN app-dev docs
([m3 — building & packaging](https://docs.canton.network/appdev/modules/m3-building-packaging)):
there is no public package registry for these, so we vendor them.

**They are committed** as real files, so JitPack and fresh clones build (codegen +
the `engine`/`testkit-it` resource bundling) with no nix. The `vendored-splice`
derivation (`../../nix/vendored.nix`) remains the source of truth and the refresh
mechanism: it builds them from pinned splice source (see below), and the devShell's
`../../nix/link-vendored.sh` only re-links when a committed copy is absent. To refresh
after a pin bump: `rm daml/dars/vendored/*.dar`, re-enter the shell, then commit the
new copies.

## Provenance

Built from source at **[canton-network/splice](https://github.com/canton-network/splice) tag `0.6.11`**
(commit `fd93f86ac`; formerly `hyperledger-labs/splice`), directories `token-standard/`
and `daml/`. Tag `0.6.11` is the first Splice release to ship the V2 (CIP-0112)
token-standard packages, and matches the `SPLICE_VERSION` pinned by `cn-quickstart`.

All packages target Daml-LF 2.1 (`--target=2.1`) and are built with dpm (SDK 3.5.2),
the toolchain the daml/ devShell provides.

## How they are produced

`nix/vendored.nix` fetches the pinned splice tree and `dpm build`s the packages in
dependency order (symlinking `<pkg>-current.dar` after each so the packages' own
`daml.yaml` data-dependencies resolve unmodified). The build needs no network (dpm's
component cache is baked into the `dpm` nix package); only the source fetch does.

The output is byte-for-byte identical to the previously-committed set. To rebuild or
inspect it directly: `nix build .#vendored-splice`. To bump the pin, change `rev` in
`nix/vendored.nix` and update its `hash` (nix will report the expected value).

> Build note — the reproducibility subtlety the derivation encodes: splice's own
> `daml/dars/` ships *prebuilt* API DARs whose package-ids differ from a fresh local
> build. The token-standard API packages + `splice-util` are built fresh and overwrite
> those, but `splice-api-featured-app-v1/-v2` are left as the prebuilt DARs — rebuilding
> them fresh changes their package-ids and breaks amulet/wallet/v1-test reproducibility.

## Contents

| Package | Ver | Notes |
|---|---|---|
| splice-api-token-metadata-v1 | 1.0.0 | shared metadata types (still V1 under V2) |
| splice-api-token-holding-v1 / -v2 | 1.0.0 | holding interface |
| splice-api-token-allocation-v1 / -v2 | 1.0.0 | allocation interface |
| splice-api-token-allocation-request-v1 / -v2 | 1.0.0 | |
| splice-api-token-allocation-instruction-v1 / -v2 | 1.0.0 | |
| splice-api-token-transfer-instruction-v1 / -v2 | 1.0.0 | |
| splice-api-token-transfer-events-v2 | 1.0.0 | new V2 tx-parsing package |
| splice-token-standard-utils | 2.0.0 | shared helpers, incl. V2 batching |

The V1 packages are included because `splice-token-standard-utils` (and the V2
compatibility rules) depend on both major versions.

## Harness DARs (for the vendored iterated-settlement test)

These back `../../external-test-sources/splice-token-standard-v2-test/` — the real
splice `splice-token-standard-v2-test` package (whose source is likewise vendored via
the derivation, per its own "copy the source into the downstream project" instruction).

| Package | Ver | Notes |
|---|---|---|
| splice-util | 0.1.7 | shared Daml utilities |
| splice-amulet | 0.1.21 | Amulet (Canton Coin) registry — implements the token-standard interfaces |
| splice-util-token-standard-wallet | 1.1.0 | wallet-side helpers incl. V2 batching |
| splice-token-standard-v1-test | 1.0.15 | V1 test harness (dep of the V2 harness) |
| splice-test-token-v1 / -v2 | 1.0.0 | reference `TestToken` registry implementations |
| splice-token-test-trading-app | 1.0.2 | OTC/DvP trading app (V1) |
| splice-token-test-trading-app-v2 | 1.0.0 | OTC/DvP trading app (V2) |

Run the worked example with `just ts-iterated` (see the repo justfile).
