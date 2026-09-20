#!/usr/bin/env bash
# setup.sh — изграждане на minij-compiler от чист checkout.
#
# Средата ползва .sh без x-бит и може да е noexec — затова всичко се вика
# през `bash`, а сборката е чист Maven (mvn package строи и prebuilt
# библиотеките: runtime/*/libruntime.{a,so} и corelib/*/libminijcore.a +
# classmap.txt спрямо corelib/pom.xml и runtime/pom.xml).
set -euo pipefail
cd "$(dirname "$0")"

echo "═══ MiniJ Compiler Setup ═══"

# 1. JDK 17+ (javac + java)
command -v javac >/dev/null 2>&1 || { echo "error: JDK 17+ (javac) не е намерен на PATH"; exit 2; }

# 2. Maven
command -v mvn >/dev/null 2>&1 || { echo "error: Maven (mvn) не е намерен на PATH"; exit 2; }

# 3. Сборка (fat jar + пре-събрани библиотеки)
bash build.sh

# 4. Регресия (scripts/run_tests.sh, args се препращат — напр. --only=md)
bash test.sh "$@"