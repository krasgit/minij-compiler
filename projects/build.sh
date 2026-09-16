#!/usr/bin/env bash
# projects/build.sh — детерминистичен преден-край за тулчейна:
#   CWD=repo-root (както test.sh), JDK 17+, absolute пътища през OUT.
# Употреба (от pom.xml, exec:exec, arguments):
#   bash projects/build.sh <target> <abs-out>
set -e
REPO="$(cd "$(dirname "$0")/.." && pwd)"        # repo-root = absolute
TARGET="${1:-arm64}"
OUT="${2:-`[ -n "$3" ] && echo "$3"`}"         # <abs-out> — подава се от pom-а

# ── JDK 16+ (instanceof pattern matching в tools/*). Debian default е 11 → детерм. избор ──
for j in /usr/lib/jvm/java-17-openjdk-amd64 \
         /usr/lib/jvm/java-17-openjdk \
         /usr/lib/jvm/java-17-openjdk-arm64; do
    [ -x "$j/bin/javac" ] && { export JAVA_HOME="$j"; break; }
done
export PATH="$JAVA_HOME/bin:$PATH"

# ── 1) сглобяване на тулчейна (еднократно; idempotентен) ──
#[ -x "$REPO/mc" ] || ( cd "$REPO" && bash ./build.sh >/dev/null )
( cd "$REPO" && bash ./build.sh >/dev/null )

# ── 2) компилиране на App.mj → нативен (CWD=REPO → rules/ се resolve-ва) ──
OUTDIR="$(dirname "$OUT")"; [ -n "$OUTDIR" ] && mkdir -p "$OUTDIR"
( cd "$REPO" && ./mc --target="$TARGET" "$REPO/projects/App.mj" -o "$OUT" )
echo "✓ compiled → $OUT (target=$TARGET)"
