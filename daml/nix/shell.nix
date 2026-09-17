{ pkgs }:

# Dev shell for daml-scratch, in the cn-quickstart style: the Daml toolchain is
# DPM (nix/dpm.nix), not the legacy `daml` assistant. `dpm` provides the 3.5.2
# compiler and the IDE language server (the Daml VS Code extension launches it
# via `dpm` — see .vscode/settings.json `daml.useDPMWhenAvailable`).
pkgs.mkShellNoCC {
  JAVA_HOME = "${pkgs.jdk21.home}";
  JAVA_OPTS = "-Xmx4g -Xss512m -XX:+UseG1GC";
  # Lay down the pinned vendored DARs / sources / OpenAPI specs (see nix/vendored.nix).
  shellHook = "${pkgs.bash}/bin/bash ${./link-vendored.sh}";
  # Fixes bash prompt/autocomplete in subshells under `nix develop`/direnv.
  packages = with pkgs; [
    canton # Digital Asset Canton open-source runtime (nodes + console)
    dpm # Daml Package Manager (`dpm`) — 3.5.2 compiler + IDE language server
    jdk21 # JDK 21 for ad-hoc `java`/console interop
    just # command runner, similar to `make`
    git
    bashInteractive
    # `code` — VS Code; `dpm studio` installs the SDK's Daml extension into it.
    # Unfree, hence allowUnfree in flake.nix; local dev only.
    vscode
  ];
}
