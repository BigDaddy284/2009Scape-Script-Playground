#!/usr/bin/env bash
# Copies Code Edition into a 2009scape single-player folder.
# Usage: ./install.sh /path/to/2009scape
set -e
TARGET="${1:?Usage: ./install.sh /path/to/2009scape}"
HERE=$(cd -- "$(dirname "$0")" >/dev/null 2>&1; pwd -P)
if [ ! -f "$TARGET/game/server.jar" ]; then
  echo "That folder does not look like the 2009scape single-player release (no game/server.jar)."
  exit 1
fi
cp -R "$HERE/overlay/." "$TARGET/"
echo "Done. Start the launcher with: java -jar \"$TARGET/codeedition/launcher.jar\""
echo "(On Windows, use \"2009 OSRS Code Edition.exe\".)"
