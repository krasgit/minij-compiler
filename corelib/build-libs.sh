#!/usr/bin/env bash
# corelib/build-libs.sh <build.directory>
#
# Builds the prebuilt MiniJ core library:
#   <build.directory>/libminijcore.a   — combined corelib object (static)
#   <build.directory>/classmap.txt     — "idx Class" lines, stable numbering
#
# The canonical driver imports every loadable corelib class in a FIXED order,
# so classIndex (and hence object-header class indices) is deterministic. Apps
# compiled with --corelib=lib pin those indices via classmap.txt and resolve
# corelib symbols externally. (Динамичен libminijcore.so изисква PIC-емисия —
# засега емитерът адресира глобални данни non-PIC, така че .so не се прави.)
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TGT="${1:-$ROOT/corelib/target}"
mkdir -p "$TGT"
TGT="$(cd "$TGT" && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/minij-corelib.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

CP="$ROOT/common/target/classes"
for d in "$ROOT"/tools/*/target/classes; do [ -d "$d" ] && CP="$CP:$d"; done
[ -d "$ROOT/driver/target/classes" ] && CP="$CP:$ROOT/driver/target/classes"
add_lib() { local f; f="$(find "$HOME/.m2/repository" -name "$1" -type f 2>/dev/null | head -1)"; [ -n "$f" ] && CP="$CP:$f"; }
add_lib 'janino-3.1.12.jar'
add_lib 'commons-compiler-3.1.12.jar'   # org.codehaus.janino:commons-compiler

CANON="$WORK/CoreLibAll.mj"
cat > "$CANON" <<'MJ'
// canonical corelib driver: imports every loadable corelib class in a FIXED
// order so classIndex (and object-header class indices) is stable across
// separate app compiles. Touching each class forces loading.
import java.lang.Math;
import java.lang.Integer;
import java.lang.Long;
import java.lang.System;
import java.util.Random;
import java.util.Arrays;

class CoreLibAll {
    static int all() {
        int x = Math.abs(-1);
        x = Integer.parseInt("42");
        long y = Long.parseLong("7");
        long t = System.currentTimeMillis();
        int[] a = new int[3];
        Arrays.sort(a);
        Random r = new Random(1L);
        int z = r.nextInt();
        return x + (int) y + z + (t > 0 ? 0 : 1);
    }
}
MJ

( cd "$ROOT" \
  && java -Dminij.home="$ROOT" -cp "$CP" bg.minij.driver.DriverMain \
       --target=arm64 --stage=asm \
       --emit-classmap="$TGT/classmap.txt" \
       "$CANON" \
  && as CoreLibAll.s -o CoreLibAll.o \
  && ar rcs "$TGT/libminijcore.a" CoreLibAll.o \
  && rm -f CoreLibAll.ast CoreLibAll.lir CoreLibAll.ssa CoreLibAll.opt.ssa \
         CoreLibAll.mir CoreLibAll.alloc.mir CoreLibAll.final.mir CoreLibAll.s CoreLibAll.o )

echo "✓ libminijcore.a + classmap.txt → $TGT"