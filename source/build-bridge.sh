#!/bin/bash
# builds codebridge.jar (server add-on) against the July 2026 server.jar and GraalJS
cd /home/claude/work/ce/codebridge && rm -rf out && mkdir out
set -o pipefail
env -u JAVA_TOOL_OPTIONS /usr/lib/jvm/java-11-openjdk-amd64/bin/javac -encoding UTF-8 --release 11 -nowarn -cp "../../upd/game/server.jar:../lib/*" -d out src/codebridge/*.java 2>&1 | { grep -v -E "Picked|Note:" || true; } || { echo BUILD FAILED; exit 1; }
cp -r res/codebridge/* out/codebridge/ && (cd out && jar cf ../codebridge.jar .) && echo built codebridge
