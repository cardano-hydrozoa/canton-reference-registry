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
