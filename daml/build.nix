# Daml + Canton sandbox — flake-parts module. Enter with `cd daml` (direnv, see .envrc) or
# `nix develop .#daml` from the repo root. Toolchain is DPM (SDK 3.5.2), not the legacy `daml`
# assistant. The overlay (nix/overlays.nix) adds `dpm` and `canton`; VS Code is unfree, so this
# module imports its own nixpkgs with allowUnfree rather than relying on the shared flake-parts pkgs.
{ inputs, ... }:
{
  perSystem =
    { system, ... }:
    let
      pkgs = import inputs.nixpkgs {
        inherit system;
        overlays = import ./nix/overlays.nix; # adds `dpm` and `canton`
        config.allowUnfree = true; # VS Code (`dpm studio` drives the `code` binary)
      };
    in
    {
      devShells.daml = import ./nix/shell.nix { inherit pkgs; };
      packages.canton = pkgs.canton;
      # Vendored token-standard DARs / sources / OpenAPI specs, built from pinned splice source
      # (replaces committed blobs). The devShell symlinks its output into the repo; see nix/shell.nix.
      packages.vendored-splice = import ./nix/vendored.nix {
        inherit pkgs;
        inherit (pkgs) dpm;
      };
    };
}
