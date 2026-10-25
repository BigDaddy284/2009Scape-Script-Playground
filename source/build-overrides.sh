#!/bin/bash
# builds overrides.jar: Code Edition's replacements for single game classes. It goes ahead of server.jar on the
# classpath, so server.jar itself is never edited.
cd /home/claude/work/ce/overrides && rm -rf out && mkdir out
set -o pipefail
env -u JAVA_TOOL_OPTIONS /usr/lib/jvm/java-11-openjdk-amd64/bin/javac -encoding UTF-8 --release 11 -nowarn -cp "../../upd/game/server.jar" -d out $(find src -name '*.java') 2>&1 | { grep -v -E "Picked|Note:" || true; } || { echo BUILD FAILED; exit 1; }
(cd out && jar cf ../overrides.jar .) && echo built overrides
