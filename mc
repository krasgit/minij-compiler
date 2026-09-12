#!/data/data/com.termux/files/usr/bin/bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
export PATH="$DIR/bin:$PATH"
TARGET="x86-64"; SRC=""; OUT="a.out"; STAGE="exe"; KEEP=0
while [ $# -gt 0 ]; do
    case "$1" in
        --target=*) TARGET="${1#*=}" ;;
        --stage=*)  STAGE="${1#*=}" ;;
        --keep)     KEEP=1 ;;
        -o)         shift; OUT="$1" ;;
        *)          if [ -z "$SRC" ]; then SRC="$1"; fi ;;
    esac
    shift
done
[ -z "$SRC" ] && { echo "usage: mc [--target=x86-64|arm64] [--stage=ast|lir|ssa|opt|mir|asm] [--keep] <src.mj> [-o out]"; exit 1; }
BASE=$(basename "$SRC" .mj)

janino-parse "$SRC" "$BASE.ast"
[ "$STAGE" = "ast" ] && { cat "$BASE.ast"; exit 0; }

ast-lower "$SRC" "$BASE.lir"
[ "$STAGE" = "lir" ] && { cat "$BASE.lir"; exit 0; }

ssa-build "$BASE.lir" "$BASE.ssa"
[ "$STAGE" = "ssa" ] && { cat "$BASE.ssa"; exit 0; }

ssa-opt "$BASE.ssa" "$BASE.opt.ssa"
[ "$STAGE" = "opt" ] && { cat "$BASE.opt.ssa"; exit 0; }

ssa-lower "$BASE.opt.ssa" "$BASE.mir"
[ "$STAGE" = "mir" ] && { cat "$BASE.mir"; exit 0; }

if [ "$TARGET" = "arm64" ]; then
    regalloc "$BASE.mir" "$BASE.alloc.mir" --target=arm64
else
    regalloc "$BASE.mir" "$BASE.alloc.mir"
fi
phi-elim "$BASE.alloc.mir" "$BASE.final.mir"

if [ "$TARGET" = "arm64" ]; then
    emit-arm "$BASE.final.mir" "$BASE.s"
else
    emit-x86 "$BASE.final.mir" "$BASE.s"
fi
[ "$STAGE" = "asm" ] && { cat "$BASE.s"; exit 0; }

if [ "$TARGET" = "arm64" ]; then
    aarch64-linux-gnu-as "$BASE.s" -o "$BASE.o" 2>/dev/null || as "$BASE.s" -o "$BASE.o"
else
    as "$BASE.s" -o "$BASE.o"
fi

# ─── link: program + runtime (crt0.o, runtime.o) + libc ────────────────────
if [ "$TARGET" = "arm64" ]; then
    CC="${AARCH64_CC:-aarch64-linux-gnu-gcc}"
    if ! command -v "$CC" >/dev/null 2>&1; then CC=gcc; fi
else
    CC="${CC:-gcc}"
fi
"$CC" -fno-stack-protector -ffreestanding -O2 -c "$DIR/runtime/runtime.c" -o "$BASE.runtime.o"
"$CC" -c "$DIR/runtime/crt0.S" -o "$BASE.crt0.o" 2>/dev/null \
    || as "$DIR/runtime/crt0.S" -o "$BASE.crt0.o"
"$CC" -nostartfiles "$BASE.o" "$BASE.crt0.o" "$BASE.runtime.o" -lc -o "$OUT"

if [ "$KEEP" = "0" ]; then
    rm -f "$BASE.ast" "$BASE.lir" "$BASE.ssa" "$BASE.opt.ssa" "$BASE.mir" "$BASE.alloc.mir" "$BASE.final.mir" "$BASE.s" "$BASE.o" "$BASE.runtime.o" "$BASE.crt0.o"
fi
echo "✓ compiled → $OUT"
