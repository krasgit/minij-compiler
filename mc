#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
export PATH="$DIR/bin:$PATH"
TARGET="x86-64"; SRC=""; OUT="a.out"; STAGE="exe"; KEEP=0; OUTDIR=""
while [ $# -gt 0 ]; do
    case "$1" in
        --target=*) TARGET="${1#*=}" ;;
        --stage=*)  STAGE="${1#*=}" ;;
        --keep)     KEEP=1 ;;
        --outdir=*) OUTDIR="${1#*=}" ;;
        -o)         shift; OUT="$1" ;;
        *)          if [ -z "$SRC" ]; then SRC="$1"; fi ;;
    esac
    shift
done
[ -z "$SRC" ] && { echo "usage: mc [--target=x86-64|arm64] [--stage=ast|lir|ssa|opt|mir|asm] [--keep] [--outdir=<dir>] <src.mj> [-o out]"; exit 1; }
BASE=$(basename "$SRC" .mj)
PROC="$BASE"; [ -n "$OUTDIR" ] && { mkdir -p "$OUTDIR"; PROC="$OUTDIR/$BASE"; }

janino-parse "$SRC" "$PROC.ast"
[ "$STAGE" = "ast" ] && { cat "$PROC.ast"; exit 0; }

ast-lower "$SRC" "$PROC.lir"
[ "$STAGE" = "lir" ] && { cat "$PROC.lir"; exit 0; }

ssa-build "$PROC.lir" "$PROC.ssa"
[ "$STAGE" = "ssa" ] && { cat "$PROC.ssa"; exit 0; }

ssa-opt "$PROC.ssa" "$PROC.opt.ssa"
[ "$STAGE" = "opt" ] && { cat "$PROC.opt.ssa"; exit 0; }

ssa-lower "$PROC.opt.ssa" "$PROC.mir"
[ "$STAGE" = "mir" ] && { cat "$PROC.mir"; exit 0; }

if [ "$TARGET" = "arm64" ]; then
    regalloc "$PROC.mir" "$PROC.alloc.mir" --target=arm64
else
    regalloc "$PROC.mir" "$PROC.alloc.mir"
fi
phi-elim "$PROC.alloc.mir" "$PROC.final.mir"

if [ "$TARGET" = "arm64" ]; then
    emit-arm "$PROC.final.mir" "$PROC.s"
else
    emit-x86 "$PROC.final.mir" "$PROC.s"
fi
[ "$STAGE" = "asm" ] && { cat "$PROC.s"; exit 0; }

if [ "$TARGET" = "arm64" ]; then
    aarch64-linux-gnu-as "$PROC.s" -o "$PROC.o" 2>/dev/null || as "$PROC.s" -o "$PROC.o"
else
    as "$PROC.s" -o "$PROC.o"
fi

# ─── link: program + runtime (crt0.o, runtime.o) + libc ────────────────────
if [ "$TARGET" = "arm64" ]; then
    CC="${AARCH64_CC:-aarch64-linux-gnu-gcc}"
    if ! command -v "$CC" >/dev/null 2>&1; then CC=gcc; fi
else
    CC="${CC:-gcc}"
fi
"$CC" -fno-stack-protector -ffreestanding -O2 -c "$DIR/runtime/runtime.c" -o "$PROC.runtime.o"
if [ "$TARGET" = "arm64" ]; then
    CRT0="$DIR/runtime/crt0.S"
else
    CRT0="$DIR/runtime/crt0-x64.S"
fi
"$CC" -c "$CRT0" -o "$PROC.crt0.o" 2>/dev/null \
    || as "$CRT0" -o "$PROC.crt0.o"
"$CC" -nostartfiles "$PROC.o" "$PROC.crt0.o" "$PROC.runtime.o" -lc -o "$OUT"

if [ "$KEEP" = "0" ]; then
    rm -f "$PROC.ast" "$PROC.lir" "$PROC.ssa" "$PROC.opt.ssa" "$PROC.mir" "$PROC.alloc.mir" "$PROC.final.mir" "$PROC.s" "$PROC.o" "$PROC.runtime.o" "$PROC.crt0.o"
fi
echo "✓ compiled → $OUT"
