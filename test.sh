#!/usr/bin/env bash
# test.sh — пълната регресия (делегира към scripts/run_tests.sh).
# Средата не дава x-бит на .sh файлове — всичко се вика през `bash`.
set -e
cd "$(dirname "$0")"
bash scripts/run_tests.sh "$@"