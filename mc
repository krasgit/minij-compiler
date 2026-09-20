#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
[ -f "$DIR/dist/target/minij-compiler.jar" ] || (cd "$DIR" && bash ./build.sh >/dev/null)
exec java -Dminij.home="$DIR" -cp "$DIR/dist/target/minij-compiler.jar" bg.minij.driver.DriverMain "$@"