#!/usr/bin/env bash
# =====================================================================================================================
# OWASP ZAP API scan (active) of the API gateway. The services publish no OpenAPI document, so zap/gen_openapi.py
# generates one for the main routes with real example ids (a fresh customer, an address, a product) and the scan
# runs with that customer's token (ZAP_AUTH_HEADER_VALUE). Accepted findings: tests/security/.zap/rules.tsv.
#   bash tests/security/zap-api.sh
# Reports: tests/security/reports/zap-api.{html,json,md} and the generated gateway-openapi.json.
# The access token lives 15 minutes, so the active scan is capped at ZAP_SCAN_MINS (12). One scanner thread with a
# small delay keeps the scan under the gateway's per-user rate limit (20 requests/s).
# Environment: ZAP_NETWORK (grocery_default), GATEWAY_URL (http://api-gateway:8080), ZAP_SCAN_MINS, DOCKER.
# =====================================================================================================================
set -uo pipefail
cd "$(dirname "$0")"
SEC="$(pwd -W 2>/dev/null || pwd)"
ZAP_IMAGE="zaproxy/zap-stable:2.17.0"
DOCKER="${DOCKER:-docker}"

# shellcheck disable=SC2086
MSYS_NO_PATHCONV=1 $DOCKER run --rm --memory "${ZAP_MEMORY:-2g}" --network "${ZAP_NETWORK:-grocery_default}" \
  -v "$SEC/reports:/zap/wrk:rw" -v "$SEC/.zap:/zap/wrk/.zap:ro" -v "$SEC/zap:/zap/scripts:ro" \
  -e GATEWAY_URL="${GATEWAY_URL:-http://api-gateway:8080}" -e SCAN_MINS="${ZAP_SCAN_MINS:-12}" \
  "$ZAP_IMAGE" sh -c '
    set -e
    python3 -I /zap/scripts/gen_openapi.py "$GATEWAY_URL" /zap/wrk/gateway-openapi.json /tmp/token
    export ZAP_AUTH_HEADER_VALUE="Bearer $(cat /tmp/token)"
    set +e
    zap-api-scan.py -t /zap/wrk/gateway-openapi.json -f openapi -I -c .zap/rules.tsv \
      -r zap-api.html -J zap-api.json -w zap-api.md \
      -z "-config view.locale=en_GB -config scanner.threadPerHost=1 -config scanner.delayInMs=40 \
          -config scanner.maxScanDurationInMins=$SCAN_MINS -config scanner.maxRuleDurationInMins=2"
    rc=$?
    echo "[zap-api] finished with exit code $rc"
    [ $rc -eq 1 ] || [ $rc -eq 3 ] && exit 1
    exit 0'
