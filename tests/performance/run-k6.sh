#!/usr/bin/env bash
# =====================================================================================================================
# Runs one Phase 13 k6 scenario in the grafana/k6 image against the running local stack (compose network).
#   bash tests/performance/run-k6.sh browse.js      # also: shop.js, checkout.js; extra args go to `k6 run`
# Environment: API_URL (http://api-gateway:8080), K6_NETWORK (grocery_default), VUS, DURATION (hold time, 2m),
#   RUN_ID (tag in all test data), SIMULATE_CLIENT_IPS (true), CANCEL_AFTER (checkout, true),
#   PROMETHEUS_RW (e.g. http://prometheus:9090/api/v1/write — streams metrics to Prometheus with remote write; the
#   Prometheus of the `observability` profile must run with --web.enable-remote-write-receiver).
# Result: the summary on stdout and tests/performance/reports/<scenario>-summary.json.
# =====================================================================================================================
set -euo pipefail
cd "$(dirname "$0")"
DIR="$(pwd -W 2>/dev/null || pwd)"
SCRIPT="${1:?usage: run-k6.sh <browse.js|shop.js|checkout.js> [k6 args]}"; shift
K6_IMAGE="grafana/k6:1.8.2"
OUT=()
if [ -n "${PROMETHEUS_RW:-}" ]; then
  OUT=(-o experimental-prometheus-rw)
fi
mkdir -p reports
MSYS_NO_PATHCONV=1 docker run --rm --memory "${K6_MEMORY:-512m}" --network "${K6_NETWORK:-grocery_default}" \
  -v "$DIR:/scripts" -w /scripts \
  -e API_URL="${API_URL:-http://api-gateway:8080}" -e RUN_ID="${RUN_ID:-p$(date +%s)}" \
  -e VUS -e DURATION -e PRODUCTS -e SIMULATE_CLIENT_IPS -e CANCEL_AFTER -e ADMIN_EMAIL -e ADMIN_PASSWORD \
  -e K6_PROMETHEUS_RW_SERVER_URL="${PROMETHEUS_RW:-}" \
  -e K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg,max" \
  -e K6_PROMETHEUS_RW_PUSH_INTERVAL=5s \
  "$K6_IMAGE" run --no-usage-report --quiet ${OUT[@]+"${OUT[@]}"} "$SCRIPT" "$@"
