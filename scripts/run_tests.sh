#!/usr/bin/env bash
# scripts/run_tests.sh — regression harness за minij-compiler.
#
# Всички examples/*.mj (плюс пакетния пример examples/imports/imports.mj) се
# компилират за <TARGET> (по подразбиране arm64) и се изпълняват — нативно на
# arm64 хост, иначе през qemu-aarch64. Сравняват се exit code и stdout.
#
#   bash scripts/run_tests.sh [--only <name>]
#   TARGET=x86-64 bash scripts/run_tests.sh
#
# Кодовете на връщане (k_throw / return 0..) и печатите са документираните
# baseline стойности от docs/CONTEXT.md (33/33 PASS).

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

TARGET="${TARGET:-arm64}"
ONLY=""
for a in "$@"; do
    case "$a" in
        --only=*) ONLY="${a#--only=}" ;;
        --only) shift; ONLY="$1" ;;
    esac
done

export JAVA_HOME="${JAVA_HOME:-}"
for j in /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-openjdk-arm64; do
    [ -x "$j/bin/javac" ] && { JAVA_HOME="$j"; break; }
done
[ -n "$JAVA_HOME" ] || { echo "error: JDK 17+ не е намерен (javac на PATH)"; exit 2; }
export PATH="$JAVA_HOME/bin:$PATH"

[ -f "dist/target/minij-compiler.jar" ] || bash ./build.sh >/dev/null

HOST_ARCH="$(uname -m)"
RUN=()
if [ "$TARGET" = "arm64" ] && [ "$HOST_ARCH" != "aarch64" ] && [ "$HOST_ARCH" != "arm64" ]; then
    QEMU_BIN="${QEMU_CMD:-qemu-aarch64}"
    command -v "$QEMU_BIN" >/dev/null || { echo "error: '$QEMU_BIN' не е намерен"; exit 2; }
    if [ -d /usr/aarch64-linux-gnu ]; then RUN=( "$QEMU_BIN" -L /usr/aarch64-linux-gnu ); else RUN=( "$QEMU_BIN" ); fi
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/mj-tests.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

pass=0; fail=0

check() {
    local name="$1" exp_rc="$2" exp_out="$3" src="$4" xtra="${5:-}"
    local bin="$WORK/$name"
    local mcargs=()
    [ -n "$xtra" ] && read -ra mcargs <<< "$xtra"
    if ! ( cd "$REPO" && timeout 180 bash mc --target="$TARGET" "${mcargs[@]}" "$src" -o "$bin" ) >/dev/null 2>&1; then
        echo "  FAIL $name (compile)"; fail=$((fail+1)); return
    fi
    local rc out
    timeout 20 "${RUN[@]}" "$bin" >"$WORK/$name.out" 2>/dev/null
    rc=$?
    out="$(tr '\n' '/' <"$WORK/$name.out")"
    if [ "$rc" = "$exp_rc" ] && [ "$out" = "$exp_out" ]; then
        echo "  PASS $name (exit $rc)"; pass=$((pass+1))
    else
        printf '  FAIL %s (expected rc=%s out=%q, got rc=%s out=%q)\n' "$name" "$exp_rc" "$exp_out" "$rc" "$out"
        fail=$((fail+1))
    fi
}

should_run() { [ -z "$ONLY" ] || [ "$1" = "$ONLY" ]; }

echo "Running regression tests (target=$TARGET)..."

if should_run hello; then  check hello    47  ""                           examples/hello.mj; fi
if should_run gcd; then    check gcd      12  ""                           examples/gcd.mj; fi
if should_run fib; then    check fib      55  ""                           examples/fib.mj; fi
if should_run forloop; then check forloop 55  ""                           examples/forloop.mj; fi
if should_run dowhile; then check dowhile 55  ""                           examples/dowhile.mj; fi
if should_run ternary; then check ternary 5   ""                           examples/ternary.mj; fi
if should_run switch; then check switch   92  ""                           examples/switch.mj; fi
if should_run print; then  check print    1   "123/-7/A/"                  examples/print.mj; fi
if should_run dbl; then    check dbl      0   "1/2/2/"                     examples/dbl.mj; fi
if should_run lng; then    check lng      0   "68/3/1/"                    examples/lng.mj; fi
if should_run mix; then    check mix      0   "6/4/"                       examples/mix.mj; fi
if should_run arrays; then check arrays   0   "30/5/6/1000000009/4/"       examples/arrays.mj; fi
if should_run oob; then    check oob      134 ""                           examples/oob.mj; fi
if should_run str; then    check str      0   "hello/world/A->101/5/hXllo/abcde//" examples/str.mj; fi
if should_run str2; then   check str2     0   $'a\tb[/line1/line2/done/-----/zzz//=/65/' examples/str2.mj; fi
if should_run md; then     check md       0   "3/4/138/12/7/3/13/5/"       examples/md.mj; fi
if should_run native; then check native   0   "14/7/5/"                    examples/native.mj; fi
if should_run str3; then   check str3     0   "1/0/0/7/abcdef/6/6/xabc/abcABcd/1/" examples/str3.mj; fi
if should_run obj; then    check obj      2   "5/7/6/12/1/2/3/1/"          examples/obj.mj; fi
if should_run obj2; then   check obj2     10  "6/7/7/17/117/7/14/8/"       examples/obj2.mj; fi
if should_run obj3; then   check obj3     26  "3/30/10/20/5/6/100/15/"     examples/obj3.mj; fi
if should_run obj4; then   check obj4     16  "1/0/0/1/7/9/0/"             examples/obj4.mj; fi
if should_run obj5; then   check obj5     77  "15/7/1/1/0/1/7/7/2/1/4/0/"  examples/obj5.mj; fi
if should_run obj6; then   check obj6     34  "18/64/8/11/-1/64/"          examples/obj6.mj; fi
if should_run obj7; then   check obj7     42  "2/2/3/4/100/100/107/2/2/"   examples/obj7.mj; fi
if should_run obj8; then   check obj8     42  "3/8/10/6/96/996/11/102/15/15/" examples/obj8.mj; fi
if should_run obj9; then   check obj9     42  "28/11/1/15/25/5/5/4/"       examples/obj9.mj; fi
if should_run obj10; then  check obj10    0   "1/2/3/1/2/42/"              examples/obj10.mj; fi
if should_run cflow; then  check cflow    0   "30/2/23/23/22/40/24/33/8/103/3/" examples/cflow.mj; fi
if should_run exc; then    check exc      3   "10/110/111/7/114/118/518/50/#0/" examples/exc.mj; fi
if should_run imports; then check imports 0   "12/9/4/10/40/12/"           examples/imports/imports.mj; fi
if should_run corelib; then
    check corelib 0 "5/7/3/4/9/1024/-4/4/314/271/42/-7/-12345/-2147483648/2147483647/10/5/-8/2147483647/1234567890123/19/9223372036854775807/-1155869325/431529176/7564655870752979346/207/0/-1465154083/78/48/8/1/[1, 2, 3, 5, 8, 9]/4/-4/[-3, -3]/4/1/1/" examples/corelib.mj
fi
# corelib като предварително компилирана библиотека (--corelib=lib): същите
# outputs като inline-а (bit-identical Random и в двата режима).
if should_run librun; then
    check librun 0 "5/7/3/4/9/1024/-4/4/314/271/42/-7/-12345/-2147483648/2147483647/10/5/-8/2147483647/1234567890123/19/9223372036854775807/-1155869325/431529176/7564655870752979346/207/0/-1465154083/78/48/8/1/[1, 2, 3, 5, 8, 9]/4/-4/[-3, -3]/4/1/1/" examples/corelib.mj "--corelib=lib"
fi
if should_run bitop; then
    check bitop 0 "16/-2147483648/2/1073741824/-4/2147483644/3/65535/-2147483648/1/-1/2147483647/0/0/0/-1/-554899859/0/1/7/6/-6/-1/0/255/-1/0/-1/4/4096/1/1/7/6/0/0/" examples/bitop.mj
fi

echo ""
echo "$pass passed, $fail failed"
[ "$fail" = "0" ]