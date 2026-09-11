[
  (self: super: {
    # Digital Asset's official Daml toolchain (compiler + IDE language server),
    # installed from get.digitalasset.com. `dpm` is the 3.x replacement for the
    # legacy `daml` assistant. Same approach as cn-quickstart's nix/dpm.nix.
    dpm = super.callPackage ./dpm.nix { };

    # Canton open-source runtime (nodes + console).
    canton = super.callPackage ./canton.nix { };
  })
]
