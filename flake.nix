{
  description = "daml-scratch — a standalone Daml + Canton sandbox for learning Daml";

  inputs = {
    flake-utils.url = "github:numtide/flake-utils";
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    # Obsidian Systems' reproducible Daml toolchain packaging (daml + dpm),
    # pinned & patchelf'd. Non-flake repo: consumed as a source input and
    # imported via its default.nix. Binary cache: s3://obsidian-open-source.
    nix-daml-sdk = {
      url = "github:obsidiansystems/nix-daml-sdk";
      flake = false;
    };
  };

  outputs =
    {
      self,
      flake-utils,
      nixpkgs,
      ...
    }@inputs:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        # allowUnfree: VS Code (below) is unfree. `daml studio` drives the `code`
        # binary specifically (VSCodium ships `codium`), so we use VS Code proper.
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
        };

        # ── Canton (Digital Asset) ─────────────────────────────────────────────
        # Canton open-source runtime: participant / sequencer / mediator nodes +
        # the Canton console. Not in nixpkgs, so fetch the release tarball and
        # wrap the launcher with a pinned LTS JDK. Canton 3.x targets Java 17–21.
        cantonVersion = "3.5.15";
        cantonJdk = pkgs.openjdk21;
        canton = pkgs.stdenv.mkDerivation {
          pname = "canton";
          version = cantonVersion;
          src = pkgs.fetchurl {
            url = "https://github.com/digital-asset/canton/releases/download/v${cantonVersion}/canton-open-source-${cantonVersion}.tar.gz";
            hash = "sha256-oRRT2YkXvmE2yy6qP4APW2fuWez/dZB/e1m0zP03Zrg=";
          };
          nativeBuildInputs = [ pkgs.makeWrapper ];
          dontConfigure = true;
          dontBuild = true;
          installPhase = ''
            runHook preInstall
            mkdir -p $out/libexec/canton $out/bin
            cp -r . $out/libexec/canton/
            makeWrapper $out/libexec/canton/bin/canton $out/bin/canton \
              --set JAVA_HOME ${cantonJdk} \
              --prefix PATH : ${cantonJdk}/bin
            runHook postInstall
          '';
          meta = {
            description = "Canton open-source runtime: sequencer/mediator (synchronizer) + participant nodes + console";
            homepage = "https://www.canton.network";
          };
        };

        # ── Daml SDK (via Obsidian's nix-daml-sdk) ─────────────────────────────
        # The Daml 3.x compiler/assistant + DPM. SDK 3.4.11 targets Daml-LF 2.1,
        # which the Canton 3.5.15 runtime accepts (Canton checks LF compat, not an
        # exact SDK match) — so we pair compiler 3.4.11 with runtime 3.5.15 and
        # build DARs with `--target=2.1`. `daml` (the assistant) is deprecated in
        # 3.x in favour of `dpm` (Daml Package Manager); both are on PATH.
        damlSdk = import inputs.nix-daml-sdk {
          inherit system;
          sdkVersion = "3.4.11";
        };

        # nix-daml-sdk's `daml` launcher hard-resets PATH to openjdk/bash/
        # coreutils only. That breaks `daml studio`, which shells out to `code`
        # (not found), and — worse — means any editor it does launch inherits a
        # stripped PATH with no git/direnv/daml/canton. Re-expose the SDK with a
        # launcher that keeps the SDK dirs first (so daml stays hermetic) but
        # *appends the caller's $PATH*, so a `daml studio` run from inside the
        # devShell hands the editor the full environment. The idiomatic launch is
        # still `nix develop -c code .` or the direnv extension (see README).
        damlSdkWithPath = pkgs.symlinkJoin {
          name = "daml-sdk-caller-path";
          paths = [ damlSdk.sdk ];
          postBuild = ''
            rm -f $out/bin/daml
            sed -E "s#(^export PATH='[^']*)'#\1:${pkgs.vscode}/bin':\"\$PATH\"#" \
              ${damlSdk.sdk}/bin/daml > $out/bin/daml
            chmod +x $out/bin/daml
          '';
        };
      in
      {
        devShells.default = pkgs.mkShell {
          JAVA_OPTS = "-Xmx4g -Xss512m -XX:+UseG1GC";
          # Fixes bash prompt/autocomplete in subshells under `nix develop`/direnv.
          buildInputs = [ pkgs.bashInteractive ];
          packages = with pkgs; [
            canton # Digital Asset Canton runtime (nodes + console)
            damlSdkWithPath # Daml SDK 3.4.11 (`daml`), launcher keeps caller PATH (git/direnv/code)
            damlSdk.dpm # Daml Package Manager (`dpm`) — the 3.x replacement for `daml`
            cantonJdk # JDK 21 for ad-hoc `java`/console interop
            just # command runner, similar to `make`
            git
            vscode # `code` — `daml studio` installs the SDK's Daml extension into it
          ];
        };
        packages.canton = canton;
      }
    );
}
