# Vendored token-standard OpenAPI specs (CIP-0112, V2)

Source: `canton-network/splice` tag `0.6.11`, `token-standard/splice-api-token-*/openapi/`
(`metadata-v1.yaml` is that tag's `token-metadata-v1.yaml`, renamed). Like the vendored
DARs, these `*.yaml` are **committed** (real files, so JitPack and fresh clones build the
`api` codegen with no nix). The `vendored-splice` derivation (`../../daml/nix/vendored.nix`)
remains the source of truth and the refresh mechanism: it produces them, and the devShell's
`link-vendored.sh` only re-links when a committed copy is absent.

The registry off-ledger API is **not one spec** — it is split per interface, each file
self-contained (schemas like `ChoiceContext`/`DisclosedContract` are duplicated per file
on purpose; upstream avoids shared/`$ref`'d defs so any OpenAPI codegen can consume them):

| file | endpoints used by the treasury/swap flow |
|---|---|
| `allocation-instruction-v2.yaml` | `getAllocationFactory` (+ alloc-instruction accept/withdraw contexts) |
| `allocation-v2.yaml` | `getSettlementFactory` (+ allocation withdraw/cancel contexts) |
| `transfer-instruction-v2.yaml` | `getTransferFactory` (+ transfer accept/reject/withdraw contexts) — used by mint |
| `metadata-v1.yaml` | registry info + list-instruments (no choice contexts) |

Note: `choiceArguments` and `choiceContextData` are free-form `type: object` — they carry
Daml values in Daml-JSON-API encoding, produced/consumed via the Daml Java codegen, not by
the OpenAPI codegen. The OpenAPI layer only models the flat envelope types.
