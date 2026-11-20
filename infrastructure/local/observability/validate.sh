#!/usr/bin/env bash
# Validates the observability configuration in Docker only (no local tools needed):
#   promtool check config + check rules + test rules (alert unit tests), amtool check-config, alloy validate.
# Usage (repository root or anywhere):  bash infrastructure/local/observability/validate.sh
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Git Bash on Windows: give Docker a Windows path and stop MSYS from rewriting container paths.
if pwd -W >/dev/null 2>&1; then DIR="$(cd "$DIR" && pwd -W)"; export MSYS_NO_PATHCONV=1; fi

PROMETHEUS_IMAGE=prom/prometheus:v3.15.1
ALERTMANAGER_IMAGE=prom/alertmanager:v0.34.2
ALLOY_IMAGE=grafana/alloy:v1.21.3

# Once per target file of the Spring services (SERVICES_RUN_ON=docker | host), mounted as docker-compose.yml does.
for MODE in docker host; do
  echo "== promtool check config (services in $MODE)"
  docker run --rm -v "$DIR/prometheus:/etc/prometheus:ro" \
    -v "$DIR/prometheus/targets/services-$MODE.yml:/etc/prometheus-services/targets.yml:ro" \
    --entrypoint /bin/promtool "$PROMETHEUS_IMAGE" check config /etc/prometheus/prometheus.yml
done

echo "== promtool check rules"
docker run --rm -v "$DIR/prometheus:/etc/prometheus:ro" --entrypoint /bin/promtool "$PROMETHEUS_IMAGE" \
  check rules /etc/prometheus/alerts.yml

echo "== promtool test rules"
docker run --rm -v "$DIR/prometheus:/etc/prometheus:ro" --entrypoint /bin/promtool "$PROMETHEUS_IMAGE" \
  test rules /etc/prometheus/tests/alerts.test.yml

echo "== amtool check-config"
docker run --rm -v "$DIR/alertmanager:/etc/alertmanager:ro" --entrypoint /bin/amtool "$ALERTMANAGER_IMAGE" \
  check-config /etc/alertmanager/alertmanager.yml

echo "== alloy validate"
docker run --rm -v "$DIR/alloy/config.alloy:/etc/alloy/config.alloy:ro" "$ALLOY_IMAGE" \
  validate /etc/alloy/config.alloy && echo "  SUCCESS"

echo "All observability checks passed."
