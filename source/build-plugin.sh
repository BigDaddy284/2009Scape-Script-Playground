#!/bin/bash
# builds the CodeWindow client plugin against the July 2026 client.jar
cd /home/claude/work/ce/codewindow && rm -rf out && mkdir out
set -o pipefail
env -u JAVA_TOOL_OPTIONS /usr/lib/jvm/java-11-openjdk-amd64/bin/javac -encoding UTF-8 --release 11 -nowarn -cp ../../upd/game/client.jar -d out src/CodeWindow/*.java 2>&1 | { grep -v -E "Picked|Note:" || true; } || { echo BUILD FAILED; exit 1; }
printf 'AUTHOR=Boss and Claude\nDESCRIPTION=Code orb that opens the Script Playground\nVERSION=0.3\n' > out/CodeWindow/plugin.properties
echo built plugin; ls out/CodeWindow
