{
  description = "daml-scratch — a standalone Daml + Canton sandbox for learning Daml";

  # Toolchain style mirrors cn-quickstart: Digital Asset's `dpm` (Daml Package
  # Manager) provides the compiler and IDE language server, installed by a plain
  # nix derivation from get.digitalasset.com (see nix/dpm.nix). This replaces the
  # earlier obsidiansystems/nix-daml-sdk setup, which topped out at SDK 3.4.11 —
  # we need 3.5.2 for the token-standard V2 (CIP-0112) packages and the IDE.
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        # allowUnfree: VS Code (in the dev shell) is unfree. `dpm studio` drives
        # the `code` binary specifically (VSCodium ships `codium`).
        pkgs = import nixpkgs {
          inherit system;
          overlays = import ./nix/overlays.nix; # adds `dpm` and `canton`
          config.allowUnfree = true;
        };
      in
      {
        devShells.default = import ./nix/shell.nix { inherit pkgs; };
        packages.canton = pkgs.canton;
      }
    );
}
