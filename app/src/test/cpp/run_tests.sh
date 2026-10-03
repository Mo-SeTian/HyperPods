#!/usr/bin/env bash
set -euo pipefail
test_dir="$(cd "$(dirname "$0")" && pwd)"
test_tmp="$(mktemp -d)"
trap 'rm -f "$test_tmp/bounded-scan-test"; rmdir "$test_tmp"' EXIT
c++ -std=c++20 -Wall -Wextra -Werror -fsanitize=address,undefined \
  "$test_dir/bounded_scan_test.cpp" -o "$test_tmp/bounded-scan-test"
"$test_tmp/bounded-scan-test" "$@"
