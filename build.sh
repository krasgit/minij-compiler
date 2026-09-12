#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
CP="lib/janino.jar:lib/commons-compiler.jar"
SRC="common/*.java"
for d in tools/*/; do
    if ls "$d"*.java >/dev/null 2>&1; then SRC="$SRC $d*.java"; fi
done
javac -cp "$CP" -d out $SRC
echo "✓ build ok"
