#!/usr/bin/env bash
# =====================================================================================================================
# Trivy configuration (IaC) scan: every Dockerfile with Trivy's built-in checks, and the Docker Compose files with
# this repository's own checks (tests/security/trivy-checks/*.rego — Trivy has no built-in Compose checks).
# Accepted findings: tests/security/.trivyignore.
#   bash tests/security/trivy-config.sh
# Reports: tests/security/reports/trivy-config.{json,txt}. Exit code 1 when an unaccepted HIGH/CRITICAL remains.
# =====================================================================================================================
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT="$(pwd -W 2>/dev/null || pwd)"
TRIVY_IMAGE="aquasec/trivy:0.76.2"
DOCKER="${DOCKER:-docker}"
SKIP="--skip-dirs **/node_modules --skip-dirs **/target --skip-dirs **/dist --skip-dirs tests/security/reports --skip-dirs tests/e2e/test-results"

# shellcheck disable=SC2086
MSYS_NO_PATHCONV=1 $DOCKER run --rm --memory "${TRIVY_MEMORY:-1g}" -v grocery-trivy-cache:/root/.cache/trivy \
  -v "$ROOT:/src:ro" -v "$ROOT/tests/security/reports:/reports" -w /src \
  --entrypoint sh "$TRIVY_IMAGE" -c "
    trivy config --quiet $SKIP --ignorefile tests/security/.trivyignore \
      --misconfig-scanners azure-arm,cloudformation,dockerfile,helm,kubernetes,terraform,terraformplan-json,terraformplan-snapshot,ansible,yaml \
      --config-check tests/security/trivy-checks --check-namespaces user \
      --format json --output /reports/trivy-config.json . &&
    trivy convert --format table --output /reports/trivy-config.txt /reports/trivy-config.json &&
    trivy convert --format table --severity HIGH,CRITICAL --exit-code 1 /reports/trivy-config.json"
