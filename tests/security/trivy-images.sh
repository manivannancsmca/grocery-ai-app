#!/usr/bin/env bash
# =====================================================================================================================
# Trivy scan of the built platform images (grocery/*:dev by default): OS packages and application dependencies
# (the Spring Boot jars, the frontend's node_modules). Accepted findings: tests/security/.trivyignore.
#   bash tests/security/trivy-images.sh                     # every local grocery/*:dev image
#   IMAGES="grocery/order-service:dev" bash tests/security/trivy-images.sh
# Reports: tests/security/reports/trivy-image-<name>.{json,txt} and trivy-images-summary.txt (HIGH/CRITICAL counts).
# Needs the Docker socket (Trivy reads the images from the local daemon). Exit code 1 when an unaccepted
# HIGH/CRITICAL with a fix remains.
# =====================================================================================================================
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT="$(pwd -W 2>/dev/null || pwd)"
TRIVY_IMAGE="aquasec/trivy:0.76.2"
DOCKER="${DOCKER:-docker}"
IMAGES="${IMAGES:-$(docker images --format '{{.Repository}}:{{.Tag}}' | grep -E '^grocery/.+:dev$' | sort -u | tr '\n' ' ')}"
[ -n "$IMAGES" ] || { echo "no grocery/*:dev images found"; exit 2; }
echo "[trivy-images] $IMAGES"

# shellcheck disable=SC2086
MSYS_NO_PATHCONV=1 $DOCKER run --rm --memory "${TRIVY_MEMORY:-2g}" -v grocery-trivy-cache:/root/.cache/trivy \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$ROOT/tests/security/.trivyignore:/cfg/.trivyignore:ro" -v "$ROOT/tests/security/reports:/reports" \
  -e IMAGES="$IMAGES" --entrypoint sh "$TRIVY_IMAGE" -c '
    status=0
    : > /reports/trivy-images-summary.txt
    for image in $IMAGES; do
      name=$(echo "$image" | sed "s|^grocery/||; s|:.*||")
      trivy image --quiet --scanners vuln --ignorefile /cfg/.trivyignore --format json \
        --output "/reports/trivy-image-$name.json" "$image" || { echo "$image: scan error" >> /reports/trivy-images-summary.txt; status=1; continue; }
      trivy convert --format table --output "/reports/trivy-image-$name.txt" "/reports/trivy-image-$name.json"
      counts=$(trivy convert --format template --template "{{range .}}{{range .Vulnerabilities}}{{.Severity}} {{if .FixedVersion}}fix{{else}}nofix{{end}}{{\"\n\"}}{{end}}{{end}}" "/reports/trivy-image-$name.json" | sort | uniq -c | tr -s " " | tr "\n" ";")
      echo "$image: $counts" >> /reports/trivy-images-summary.txt
      # the gate: an unaccepted HIGH/CRITICAL that has a fixed version
      echo "$counts" | grep -Eq "(HIGH|CRITICAL) fix" && status=1
    done
    cat /reports/trivy-images-summary.txt
    exit $status'
