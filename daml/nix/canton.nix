{
  stdenv,
  fetchurl,
  makeWrapper,
  jdk21,
}:

# Canton open-source runtime: participant / sequencer / mediator nodes + the
# Canton console. Not in nixpkgs, so we fetch the release tarball and wrap the
# launcher with a pinned LTS JDK. Canton 3.x targets Java 17–21.
#
# The runtime (3.5.x) is intentionally a different version from the DPM SDK
# compiler: Canton checks Daml-LF compatibility, not an exact SDK match, so the
# DPM 3.5.2 compiler (LF 2.1) pairs fine with this runtime.
let
  cantonVersion = "3.5.15";
in
stdenv.mkDerivation {
  pname = "canton";
  version = cantonVersion;
  src = fetchurl {
    url = "https://github.com/digital-asset/canton/releases/download/v${cantonVersion}/canton-open-source-${cantonVersion}.tar.gz";
    hash = "sha256-oRRT2YkXvmE2yy6qP4APW2fuWez/dZB/e1m0zP03Zrg=";
  };
  nativeBuildInputs = [ makeWrapper ];
  dontConfigure = true;
  dontBuild = true;
  installPhase = ''
    runHook preInstall
    mkdir -p $out/libexec/canton $out/bin
    cp -r . $out/libexec/canton/
    makeWrapper $out/libexec/canton/bin/canton $out/bin/canton \
      --set JAVA_HOME ${jdk21} \
      --prefix PATH : ${jdk21}/bin
    runHook postInstall
  '';
  meta = {
    description = "Canton open-source runtime: sequencer/mediator (synchronizer) + participant nodes + console";
    homepage = "https://www.canton.network";
  };
}
