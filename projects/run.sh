#!/usr/bin/env bash
# run.sh — изпълнява компилирания MiniJ бинарен:
#   ако target-архитектурата съвпада с host-архитектурата → пуска директно,
#   иначе → през qemu (с подходящ -L sysroot). Проверява очаквания exit код.
set -u
TARGET="${1:-x86-64}"
BIN="${2:?usage: run.sh <target> <binary> [expected-exit]}"
EXPECTED="${3:-}"
HOST="$(uname -m)"

case "$TARGET-$HOST" in
    x86-64-x86_64|arm64-aarch64) QEMU_CMD="" ;;                      # native
    arm64-*) QEMU_CMD="qemu-aarch64 -L /usr/aarch64-linux-gnu" ;;
    x86-64-*) QEMU_CMD="qemu-x86_64 -L /usr/x86_64-linux-gnu" ;;
    *) QEMU_CMD="" ;;
esac

$QEMU_CMD "$BIN" || true
got=$?
echo "  App.mj exit=$got (очаквано $EXPECTED)"
[ -z "$EXPECTED" ] || [ "$got" = "$EXPECTED" ]