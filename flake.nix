{
  description = "daml-scratch — a Daml + Canton sandbox and a Scala port workbench, sharing one flake";

  # Single top-level flake, flake-parts style (cf. github.com/mlabs-haskell/lambda-buffers):
  # each subproject contributes its own dev shell from its own `build.nix` module, wired in via
  # `imports` below. One nixpkgs, one flake.lock for the whole repo. Per-directory `.envrc`s each
  # select their shell with `use flake ..#<name>`.
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
    flake-parts.url = "github:hercules-ci/flake-parts";
  };

  outputs =
    inputs@{ flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      imports = [
        ./daml/build.nix
        ./scala/build.nix
      ];
      systems = [
        "x86_64-linux"
        "x86_64-darwin"
        "aarch64-linux"
        "aarch64-darwin"
      ];
    };
}
