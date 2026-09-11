# Vendored DARs — Canton Network Token Standard (V1 + V2 / CIP-0112)

These `.dar` files are checked in as `data-dependencies` (see `../../daml.yaml`),
following the approach recommended in the CN app-dev docs
([m3 — building & packaging](https://docs.canton.network/appdev/modules/m3-building-packaging)):
there is no public package registry for these, and DARs are small and change
infrequently, so we vendor them.

## Provenance

Built from source at **[hyperledger-labs/splice](https://github.com/hyperledger-labs/splice) tag `0.6.11`**,
directory `token-standard/`. Tag `0.6.11` is the first Splice release to ship the
V2 (CIP-0112) token-standard packages, and matches the `SPLICE_VERSION` pinned by
`cn-quickstart`.

All packages are built with `--target=2.1` (Daml-LF 2.1), so the daml-scratch
SDK 3.4.11 / Canton 3.5.15 toolchain reads them without an SDK bump.

## How they were produced

```sh
# sparse, blobless checkout of just the token-standard tree
git clone --filter=blob:none --sparse https://github.com/hyperledger-labs/splice.git
cd splice && git sparse-checkout set token-standard && git checkout 0.6.11
cd token-standard

# build each package in dependency order with dpm (SDK 3.5.2), symlinking
# `<pkg>-current.dar` after each build so the packages' own daml.yaml
# data-dependencies (which reference `-current.dar`) resolve unmodified.
# See scratch build loop; burn-mint-v1 is an empty stub in 0.6.11 and is skipped.
```

The resulting `.daml/dist/<pkg>-<version>.dar` files were copied here.

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
splice `splice-token-standard-v2-test` package (whose source is vendored there, per
its own "copy the source into the downstream project" instruction). All built from
the same splice `0.6.11` tree.

| Package | Ver | Notes |
|---|---|---|
| splice-util | 0.1.7 | shared Daml utilities |
| splice-amulet | 0.1.21 | Amulet (Canton Coin) registry — implements the token-standard interfaces |
| splice-util-token-standard-wallet | 1.1.0 | wallet-side helpers incl. V2 batching |
| splice-token-standard-v1-test | 1.0.15 | V1 test harness (dep of the V2 harness) |
| splice-test-token-v1 / -v2 | 1.0.0 | reference `TestToken` registry implementations |
| splice-token-test-trading-app | 1.0.2 | OTC/DvP trading app (V1) |
| splice-token-test-trading-app-v2 | 1.0.0 | OTC/DvP trading app (V2) |

> Build note: `daml/dars/` in the splice tree ships prebuilt API DARs whose
> package-ids differ from a fresh local build. To avoid "same unit id, conflicting
> package id" errors, the whole set here was built together from source with the
> freshly-built API DARs used consistently everywhere.

Run the worked example with `just ts-iterated` (see the repo justfile).
