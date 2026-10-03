#!/usr/bin/env bash
# Exercise deployment readiness decisions without starting Docker or reading .env.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/deploy.sh"

compose() {
  if [[ "$*" == 'ps --all --quiet '* ]]; then
    [[ "$scenario" == inspect-query-failure ]] && return 1
    [[ "$scenario" == missing && "$4" == "$tested_service" ]] && return 0
    printf '%s\n' "$4"
  fi
  return 0
}

docker() {
  [[ "$1" == inspect ]] || { echo "Unexpected Docker command" >&2; return 1; }
  [[ "$scenario" == inspect-failure ]] && return 1
  if [[ "$4" == "$tested_service" ]]; then
    case "$scenario" in
      starting) [[ "$sleeps" == 0 ]] && { echo 'running starting'; return; } ;;
      timeout) echo 'running starting'; return ;;
      unhealthy) echo 'running unhealthy'; return ;;
      stopped) echo 'exited healthy'; return ;;
      no-healthcheck) echo 'running missing'; return ;;
    esac
  fi
  echo 'running healthy'
}

sleep() { sleeps=$((sleeps + 1)); }

run_case() {
  local scenario=$1 expected=$2 expected_sleeps=$3 sleeps=0 result=0 tested_service=${4:-backend}
  local DEPLOY_HEALTH_TIMEOUT_SECONDS=10
  [[ "$scenario" == invalid-timeout ]] && DEPLOY_HEALTH_TIMEOUT_SECONDS=invalid
  wait_healthy >/dev/null 2>&1 || result=$?
  if [[ "$result" != "$expected" || "$sleeps" != "$expected_sleeps" ]]; then
    printf 'FAIL %s: exit=%s sleeps=%s (expected %s/%s)\n' "$scenario" "$result" "$sleeps" "$expected" "$expected_sleeps" >&2
    return 1
  fi
  printf 'PASS %s\n' "$scenario"
}

run_case healthy 0 0
run_case starting 0 1
run_case unhealthy 1 0
run_case stopped 1 0
run_case no-healthcheck 1 0
run_case timeout 1 2
run_case missing 1 2
run_case inspect-query-failure 1 0
run_case inspect-failure 1 0
run_case invalid-timeout 1 0
run_case unhealthy 1 0 business-service
run_case missing 1 2 business-service
