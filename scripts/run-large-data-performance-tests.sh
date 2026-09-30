#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "${script_dir}/.." && pwd)"

cd "${project_root}/backend"
RUN_LARGE_DATA_TESTS=true mvn -Dtest=LargeDataHistoryPerformanceTest test
