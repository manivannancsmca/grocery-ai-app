#!/usr/bin/env bash
# =====================================================================================================================
# Trivy filesystem scan of the repository: dependency vulnerabilities (Maven pom.xml, npm package-lock.json) and
# secrets. Accepted findings: tests/security/.trivyignore (each with a reason and an expiry date).
#   bash tests/security/trivy-fs.sh
# Reports: tests/security/reports/trivy-fs.{json,txt}. Exit code 1 when an unaccepted HIGH/CRITICAL remains.
# The vulnerability DB is cached in the docker volume grocery-trivy-cache. Maven poms are resolved from the local
# Maven repository (docker volume TRIVY_M2, default grocery-m2 — the one the Maven builds use) with --offline-scan:
# Maven Central blocks an IP for 30 minutes after a burst of pom downloads (429), which a full scan easily triggers.
# =====================================================================================================================
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT="$(pwd -W 2>/dev/null || pwd)"
TRIVY_IMAGE="aquasec/trivy:0.76.2"
DOCKER="${DOCKER:-docker}"
SKIP="--skip-dirs **/node_modules --skip-dirs **/target --skip-dirs **/dist --skip-dirs tests/security/reports --skip-dirs tests/e2e/test-results --skip-dirs tests/e2e/playwright-report --skip-dirs tests/performance/reports"

# shellcheck disable=SC2086
MSYS_NO_PATHCONV=1 $DOCKER run --rm --memory "${TRIVY_MEMORY:-2g}" -v grocery-trivy-cache:/root/.cache/trivy \
  -v "${TRIVY_M2:-grocery-m2}:/root/.m2:ro" \
  -v "$ROOT:/src:ro" -v "$ROOT/tests/security/reports:/reports" -w /src \
  --entrypoint sh "$TRIVY_IMAGE" -c "
    trivy fs --quiet --offline-scan --list-all-pkgs --scanners vuln,secret $SKIP --ignorefile tests/security/.trivyignore \
      --format json --output /reports/trivy-fs.json . &&
    trivy convert --format table --output /reports/trivy-fs.txt /reports/trivy-fs.json &&
    trivy convert --format table --severity HIGH,CRITICAL --exit-code 1 /reports/trivy-fs.json"
