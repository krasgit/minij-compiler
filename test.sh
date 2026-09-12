#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"
./build.sh
pass=0; fail=0
run() {
    name="$1"; src="$2"; expected="$3"
    if ! ./mc "$src" -o "t_$name" >/dev/null 2>&1; then
        echo "  FAIL $name (compile)"; fail=$((fail+1)); return
    fi
    ./t_$name
    got=$?
    if [ "$got" = "$expected" ]; then
        echo "  PASS $name (exit $got)"; pass=$((pass+1))
    else
        echo "  FAIL $name (expected $expected got $got)"; fail=$((fail+1))
    fi
    rm -f "t_$name"
}
echo "Running regression tests..."
run hello   examples/hello.mj   47
run gcd     examples/gcd.mj     12
run fib     examples/fib.mj     55
run forloop examples/forloop.mj 55
echo ""
echo "$pass passed, $fail failed"
[ "$fail" = "0" ] || exit 1
