#!/usr/bin/env bash
set -e
# runtime/build-libs.sh — произвежда libruntime.a и libruntime.so (runtime.c + natives.c).
# Употреба: bash build-libs.sh [<outdir>]   (по подразбиране target/)
cd "$(dirname "$0")"
OUTDIR="${1:-target}"
mkdir -p "$OUTDIR"
CC="${CC:-gcc}"
CFLAGS="-fno-stack-protector -ffreestanding -O2"

# ── static lib: libruntime.a ──
"$CC" $CFLAGS -c runtime.c -o "$OUTDIR/runtime.o"
"$CC" $CFLAGS -c natives.c -o "$OUTDIR/natives.o"
ar rcs "$OUTDIR/libruntime.a" "$OUTDIR/runtime.o" "$OUTDIR/natives.o"

# ── shared lib: libruntime.so (PIC) ──
"$CC" $CFLAGS -fPIC -c runtime.c -o "$OUTDIR/runtime.pic.o"
"$CC" $CFLAGS -fPIC -c natives.c -o "$OUTDIR/natives.pic.o"
"$CC" -shared -o "$OUTDIR/libruntime.so" "$OUTDIR/runtime.pic.o" "$OUTDIR/natives.pic.o" -lc -lm
echo "✓ libruntime.a + libruntime.so → $OUTDIR"
