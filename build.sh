#!/bin/sh
# Builds build/fotoshi.jar; "./build.sh install" also copies it into JOSM's plugins folder.
# JOSM_JAR is the JOSM to build against (on macOS, the installed app's by default), VERSION the plugin version.
set -e
cd "$(dirname "$0")"
if [ "$(uname)" = Darwin ]; then
  JOSM_JAR=${JOSM_JAR:-/Applications/JOSM.app/Contents/app/josm-custom.jar}
  JOSM_PLUGINS=${JOSM_PLUGINS:-$HOME/Library/JOSM/plugins}
else
  JOSM_PLUGINS=${JOSM_PLUGINS:-$HOME/.local/share/JOSM/plugins}
fi
[ -f "$JOSM_JAR" ] || { echo "Set JOSM_JAR to JOSM's jar (josm-tested.jar)"; exit 1; }
rm -rf build && mkdir -p build/classes
javac --release 11 -proc:none -encoding UTF-8 -Xlint:all,-serial -cp "$JOSM_JAR" -d build/classes $(find src -name '*.java')
{ cat MANIFEST.MF; echo "Plugin-Version: ${VERSION:-dev}"; echo "Plugin-Date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > build/MANIFEST.MF
jar cfm build/fotoshi.jar build/MANIFEST.MF -C build/classes . images
echo "Built build/fotoshi.jar"
if [ "$1" = install ]; then
  mkdir -p "$JOSM_PLUGINS" && cp build/fotoshi.jar "$JOSM_PLUGINS/"
  echo "Installed $JOSM_PLUGINS/fotoshi.jar"
fi
