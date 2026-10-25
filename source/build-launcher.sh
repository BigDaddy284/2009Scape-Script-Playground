#!/bin/bash
# builds codeedition/launcher.jar (launcher + Script Playground, with FlatLaf and RSyntaxTextArea inside)
cd /home/claude/work/ce/launcher && rm -rf out && mkdir out
set -o pipefail
env -u JAVA_TOOL_OPTIONS /usr/lib/jvm/java-11-openjdk-amd64/bin/javac -encoding UTF-8 --release 11 -nowarn -cp "../uilib/lib/*" -d out src/codeedition/*.java 2>&1 | { grep -v -E "Picked|Note:" || true; } || { echo BUILD FAILED; exit 1; }
(cd out && for j in ../../uilib/lib/*.jar; do unzip -qo $j -x 'META-INF/*'; done)
printf 'Main-Class: codeedition.Launcher\n' > manifest.txt
(cd out && jar cfm ../launcher.jar ../manifest.txt .) && echo built launcher && ls -la launcher.jar
