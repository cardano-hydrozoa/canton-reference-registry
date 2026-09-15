# Scala port workbench — flake-parts module. Enter with `cd scala` (direnv, see .envrc) or
# `nix develop .#scala` from the repo root. Toolchain ported from the hydrozoa repo's flake. The
# git-hooks precommit (scalafixAll/scalafmtCheck) is intentionally omitted for now: with no
# build.sbt yet it would fail on every shell entry. Add it back once a Scala project exists here.
{ inputs, ... }:
{
  perSystem =
    { system, ... }:
    let
      pkgs = import inputs.nixpkgs { inherit system; };
      jdk = pkgs.openjdk25;
      # The nixpkgs sbt launcher (1.x) reads project/build.properties and bootstraps whatever
      # sbt version it names — including sbt 2.x — so no launcher pin is needed here.
      sbt0 = pkgs.sbt.override { jre = jdk; };
      # sbt 2's `bspConfig` writes `.bsp/sbt.json` with argv `[<sbt>, "bsp"]`, but the nixpkgs
      # launcher script has no `bsp` handling and sbt 2 has no `bsp` command, so IDEA's BSP sync
      # (`sbt bsp`) dies on startup and the import times out. sbt only starts its BSP server via
      # the `-bsp` launcher flag, so translate a bare `bsp` arg to `-bsp`. Pin `sbt.script` to
      # this wrapper so `bspConfig` records the wrapper (not the inner launcher) in .bsp/sbt.json.
      sbtBspShim = pkgs.writeShellScriptBin "sbt" ''
        self="$(readlink -f "$0")"
        args=()
        for a in "$@"; do
          [ "$a" = "bsp" ] && a="-bsp"
          args+=("$a")
        done
        exec ${sbt0}/bin/sbt "-Dsbt.script=$self" "''${args[@]}"
      '';
      # The nixpkgs `sbt` package also bundles the `sbtn` thin client (sbt 1.x), which cannot
      # drive an sbt 2 server (it reports `unknown event: sbt/exec`). Strip it so only `sbt` is on
      # PATH — nobody should reach for the broken client by habit. Restore once nixpkgs ships an
      # sbt 2 `sbtn`. `sbt` itself is the BSP shim above.
      sbtNoSbtn = pkgs.symlinkJoin {
        name = "sbt-no-sbtn";
        paths = [ sbt0 ];
        postBuild = ''
          rm -f $out/bin/sbtn $out/bin/sbt
          ln -s ${sbtBspShim}/bin/sbt $out/bin/sbt
        '';
      };
      visualvm = pkgs.visualvm.override { jdk = jdk; };
    in
    {
      devShells.scala = pkgs.mkShell {
        JAVA_OPTS = "-Xmx4g -Xss512m -XX:+UseG1GC";
        # Lay down the pinned vendored DARs / OpenAPI specs the build consumes (../daml/nix/vendored.nix).
        shellHook = "${pkgs.bash}/bin/bash ${../daml/nix/link-vendored.sh}";
        # This fixes bash prompt/autocomplete issues with subshells (i.e. in VSCode) under `nix develop`/direnv
        buildInputs = [ pkgs.bashInteractive ];
        packages = with pkgs; [
          ammonite # modernized scala repl: https://ammonite.io/
          async-profiler # Low-overhead profiler for the JVM: https://github.com/async-profiler/async-profiler
          git # otherwise `git` resolves to the broken macOS Xcode shim inside `nix develop`
          jdk
          just # command runner, similar to `make`
          libnotify # used in justfile
          ltex-ls # Language server for markdown: https://github.com/valentjn/ltex-ls
          nixfmt
          sbtNoSbtn
          scala-cli
          scalafix
          scalafmt
          # Visualize programs running on the JVM. May need _JAVA_AWT_WM_NONREPARENTING=1 on wayland:
          #    https://github.com/oracle/visualvm/issues/403
          visualvm
          nodejs_24 # this is needed by IDEA's MCP Server
          mermaid-cli
        ];
      };
    };
}
