# Vendored Canton Network Token Standard (CIP-0112) build inputs, produced from a pinned
# splice checkout instead of committed blobs. Output layout:
#   $out/dars/*.dar                 — the 21 DARs daml/ + scala/ depend on
#   $out/external-test-sources/daml — pristine splice-token-standard-v2-test source tree
#   $out/openapi/*.yaml             — the 4 registry OpenAPI specs the Scala codegen consumes
# The devShell (nix/shell.nix) symlinks these into the repo at their expected paths; the
# generated files are gitignored. See dars/vendored/README.md for provenance.
#
# Reproducibility (verified byte-for-byte against the previously-committed set): the token-standard
# API packages + splice-util are built fresh from source and OVERWRITE splice's prebuilt daml/dars/,
# but splice-api-featured-app-v1/-v2 must stay the ORIGINAL prebuilt DARs — building them fresh
# yields different package-ids and breaks amulet/wallet/v1-test. The build needs no network (dpm's
# component cache is baked into the dpm package); only the source fetch touches the network.
{ pkgs, dpm }:

let
  splice = pkgs.fetchFromGitHub {
    owner = "canton-network";
    repo = "splice";
    rev = "0.6.11"; # commit fd93f86ac
    # Sparse: only the two trees the build touches. Keeps the fetch small and the hash stable.
    sparseCheckout = [ "token-standard" "daml" ];
    hash = "sha256-UyL5qxhsx6j8GOECwsNZDhakyIUNCHgDhHy21OuR3EQ=";
  };

  # token-standard API packages, in dependency order. Built fresh, symlinked as <pkg>-current.dar,
  # and (all but utils) copied over splice's prebuilt daml/dars/ so the v1-consuming harness links
  # against the fresh ids.
  apiPkgs = [
    "splice-api-token-metadata-v1:1.0.0"
    "splice-api-token-holding-v1:1.0.0"
    "splice-api-token-holding-v2:1.0.0"
    "splice-api-token-transfer-events-v2:1.0.0"
    "splice-api-token-allocation-v1:1.0.0"
    "splice-api-token-allocation-v2:1.0.0"
    "splice-api-token-allocation-request-v1:1.0.0"
    "splice-api-token-allocation-request-v2:1.0.0"
    "splice-api-token-allocation-instruction-v1:1.0.0"
    "splice-api-token-allocation-instruction-v2:1.0.0"
    "splice-api-token-transfer-instruction-v1:1.0.0"
    "splice-api-token-transfer-instruction-v2:1.0.0"
    "splice-token-standard-utils:2.0.0"
  ];
  # The 13th (utils) is fresh-built but not copied into daml/dars/ (nothing there references it).
  apiOverwrite = pkgs.lib.init apiPkgs;

  # The 21 collected DARs: "<subdir-under-source>:<pkg>:<version>".
  collected = [
    "token-standard/splice-api-token-metadata-v1:splice-api-token-metadata-v1:1.0.0"
    "token-standard/splice-api-token-holding-v1:splice-api-token-holding-v1:1.0.0"
    "token-standard/splice-api-token-holding-v2:splice-api-token-holding-v2:1.0.0"
    "token-standard/splice-api-token-transfer-events-v2:splice-api-token-transfer-events-v2:1.0.0"
    "token-standard/splice-api-token-allocation-v1:splice-api-token-allocation-v1:1.0.0"
    "token-standard/splice-api-token-allocation-v2:splice-api-token-allocation-v2:1.0.0"
    "token-standard/splice-api-token-allocation-request-v1:splice-api-token-allocation-request-v1:1.0.0"
    "token-standard/splice-api-token-allocation-request-v2:splice-api-token-allocation-request-v2:1.0.0"
    "token-standard/splice-api-token-allocation-instruction-v1:splice-api-token-allocation-instruction-v1:1.0.0"
    "token-standard/splice-api-token-allocation-instruction-v2:splice-api-token-allocation-instruction-v2:1.0.0"
    "token-standard/splice-api-token-transfer-instruction-v1:splice-api-token-transfer-instruction-v1:1.0.0"
    "token-standard/splice-api-token-transfer-instruction-v2:splice-api-token-transfer-instruction-v2:1.0.0"
    "token-standard/splice-token-standard-utils:splice-token-standard-utils:2.0.0"
    "daml/splice-util:splice-util:0.1.7"
    "daml/splice-amulet:splice-amulet:0.1.21"
    "daml/splice-util-token-standard-wallet:splice-util-token-standard-wallet:1.1.0"
    "token-standard/examples/splice-test-token-v1:splice-test-token-v1:1.0.0"
    "token-standard/examples/splice-test-token-v2:splice-test-token-v2:1.0.0"
    "token-standard/examples/splice-token-test-trading-app:splice-token-test-trading-app:1.0.2"
    "token-standard/examples/splice-token-test-trading-app-v2:splice-token-test-trading-app-v2:1.0.0"
    "token-standard/splice-token-standard-v1-test:splice-token-standard-v1-test:1.0.15"
  ];
in
pkgs.stdenv.mkDerivation {
  pname = "vendored-splice";
  version = "0.6.11";
  src = splice;

  nativeBuildInputs = [ dpm ]; # sets DPM_HOME (with the baked component cache) via its setup-hook

  dontConfigure = true;

  # Space-separated lists for the shell loops below.
  apiPkgList = builtins.concatStringsSep " " apiPkgs;
  apiOverwriteList = builtins.concatStringsSep " " apiOverwrite;
  collectedList = builtins.concatStringsSep " " collected;

  buildPhase = ''
    runHook preBuild
    export HOME="$TMPDIR"                 # dpm writes caches/logs under $HOME, not DPM_HOME
    TS="$PWD/token-standard"
    DAML="$PWD/daml"

    sym() { ln -sf "$2-$3.dar" "$1/.daml/dist/$2-current.dar"; }   # dir pkg ver
    build_pkg() { ( cd "$1" && dpm build ); }

    # 1. token-standard API, fresh, symlinking -current after each so downstream deps resolve.
    for pv in $apiPkgList; do
      p="''${pv%:*}"; v="''${pv#*:}"
      build_pkg "$TS/$p"; sym "$TS/$p" "$p" "$v"
    done

    # 2. Overwrite splice's prebuilt daml/dars/ with the fresh API builds. featured-app v1/v2 are
    #    deliberately left as the prebuilt DARs (see header).
    for pv in $apiOverwriteList; do
      p="''${pv%:*}"; v="''${pv#*:}"
      cp -f "$TS/$p/.daml/dist/$p-$v.dar" "$DAML/dars/$p-$v.dar"
    done

    # 3. support libs.
    build_pkg "$DAML/splice-util";                     sym "$DAML/splice-util" splice-util 0.1.7
    build_pkg "$DAML/splice-api-reward-assignment-v1"; sym "$DAML/splice-api-reward-assignment-v1" splice-api-reward-assignment-v1 1.0.0

    # 4. amulet (fresh API + prebuilt featured-app).
    build_pkg "$DAML/splice-amulet";                   sym "$DAML/splice-amulet" splice-amulet 0.1.21

    # 5. examples: test tokens + trading apps.
    build_pkg "$TS/examples/splice-test-token-v1";           sym "$TS/examples/splice-test-token-v1" splice-test-token-v1 1.0.0
    build_pkg "$TS/examples/splice-test-token-v2";           sym "$TS/examples/splice-test-token-v2" splice-test-token-v2 1.0.0
    build_pkg "$TS/examples/splice-token-test-trading-app";    sym "$TS/examples/splice-token-test-trading-app" splice-token-test-trading-app 1.0.2
    build_pkg "$TS/examples/splice-token-test-trading-app-v2"; sym "$TS/examples/splice-token-test-trading-app-v2" splice-token-test-trading-app-v2 1.0.0

    # 6. wallet.
    build_pkg "$DAML/splice-util-token-standard-wallet"; sym "$DAML/splice-util-token-standard-wallet" splice-util-token-standard-wallet 1.1.0

    # 7. v1-test (needs amulet + trading-app + test-token-v1 + util).
    build_pkg "$TS/splice-token-standard-v1-test";       sym "$TS/splice-token-standard-v1-test" splice-token-standard-v1-test 1.0.15

    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    mkdir -p "$out/dars" "$out/external-test-sources" "$out/openapi"

    for dpv in $collectedList; do
      dir="''${dpv%%:*}"; rest="''${dpv#*:}"; p="''${rest%:*}"; v="''${rest#*:}"
      cp "$dir/.daml/dist/$p-$v.dar" "$out/dars/$p-$v.dar"
    done

    # Pristine splice-token-standard-v2-test source (Daml-Script harness; consumed by copying, not
    # via .dar). The repo overlays only its own daml.yaml on top of this.
    cp -r token-standard/splice-token-standard-v2-test/daml "$out/external-test-sources/daml"

    # Registry OpenAPI specs. metadata's file is renamed (splice: token-metadata-v1.yaml).
    cp token-standard/splice-api-token-metadata-v1/openapi/token-metadata-v1.yaml "$out/openapi/metadata-v1.yaml"
    cp token-standard/splice-api-token-allocation-v2/openapi/allocation-v2.yaml "$out/openapi/allocation-v2.yaml"
    cp token-standard/splice-api-token-allocation-instruction-v2/openapi/allocation-instruction-v2.yaml "$out/openapi/allocation-instruction-v2.yaml"
    cp token-standard/splice-api-token-transfer-instruction-v2/openapi/transfer-instruction-v2.yaml "$out/openapi/transfer-instruction-v2.yaml"

    runHook postInstall
  '';

  dontFixup = true;
}
