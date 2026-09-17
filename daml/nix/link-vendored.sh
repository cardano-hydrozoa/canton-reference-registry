#!/usr/bin/env bash
# Link the pinned vendored-splice build outputs (nix/vendored.nix) into their repo paths. Run from
# both devShells' shellHook — the DARs are consumed by the daml build AND the Scala Canton
# container/codegen, so either shell must be able to lay them down. The linked targets are
# gitignored; the derivation is the source of truth. Non-fatal: warns and returns if the build is
# unavailable (e.g. offline with an unpopulated store), leaving any existing links in place.
set -uo pipefail

root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "vendored: not in a git tree; skipping vendored-DAR linking" >&2
  exit 0
}

out="$(nix build "$root#vendored-splice" --no-link --print-out-paths 2>/dev/null)"
if [ -z "${out:-}" ] || [ ! -d "$out/dars" ]; then
  echo "vendored: could not build .#vendored-splice (offline?); leaving existing links" >&2
  exit 0
fi

# 21 DARs → daml/dars/vendored/ (README.md stays committed alongside).
mkdir -p "$root/daml/dars/vendored"
for f in "$out"/dars/*.dar; do
  ln -sfn "$f" "$root/daml/dars/vendored/$(basename "$f")"
done

# Pristine splice-token-standard-v2-test source → external-test-sources/ (daml.yaml stays committed).
ln -sfn "$out/external-test-sources/daml" \
  "$root/daml/external-test-sources/splice-token-standard-v2-test/daml"

# 4 registry OpenAPI specs → scala/registry-openapi/ (README.md stays committed).
mkdir -p "$root/scala/registry-openapi"
for f in "$out"/openapi/*.yaml; do
  ln -sfn "$f" "$root/scala/registry-openapi/$(basename "$f")"
done
