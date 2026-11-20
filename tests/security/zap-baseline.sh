#!/usr/bin/env bash
# =====================================================================================================================
# OWASP ZAP baseline (passive) scans of the frontend and the API gateway, with the accepted-findings config
# tests/security/.zap/rules.tsv. Reports: tests/security/reports/zap-baseline-{frontend,gateway}.{html,json,md}
#   bash tests/security/zap-baseline.sh
# The ZAP container joins the compose network. The frontend is addressed by its container IP because Vite's dev server
# only answers Host headers it allows (localhost and IP addresses); a built frontend (Phase 14) can use its name:
#   FRONTEND_URL=http://frontend:80 bash tests/security/zap-baseline.sh
# Environment: ZAP_NETWORK (grocery_default), FRONTEND_URL, GATEWAY_URL (http://api-gateway:8080), ZAP_MINS (spider
# minutes, 3), ZAP_AJAX (true: also the browser-based AJAX spider, the SPA's routes only exist in JavaScript),
# DOCKER (docker command or a throttling wrapper). Exit code: 1 when a rule configured FAIL fires, else 0.
# =====================================================================================================================
set -uo pipefail
cd "$(dirname "$0")"
SEC="$(pwd -W 2>/dev/null || pwd)"
ZAP_IMAGE="zaproxy/zap-stable:2.17.0"
DOCKER="${DOCKER:-docker}"
NETWORK="${ZAP_NETWORK:-grocery_default}"
GATEWAY_URL="${GATEWAY_URL:-http://api-gateway:8080}"
MINS="${ZAP_MINS:-3}"
AJAX=""; [ "${ZAP_AJAX:-true}" = true ] && AJAX="-j"
status=0

run() { # name target
  # ZAP_MEMORY 3g: with 2g the AJAX spider's browser was killed mid-scan (ZAP exit 3, no report).
  # shellcheck disable=SC2086
  MSYS_NO_PATHCONV=1 $DOCKER run --rm --memory "${ZAP_MEMORY:-3g}" --network "$NETWORK" \
    -v "$SEC/reports:/zap/wrk:rw" -v "$SEC/.zap:/zap/wrk/.zap:ro" \
    -e TARGET="$2" -e NAME="$1" -e MINS="$MINS" -e AJAX="$AJAX" \
    "$ZAP_IMAGE" sh -c '
      t="$TARGET"
      case "$t" in auto-frontend) t="http://$(getent hosts frontend | cut -d" " -f1):6420/";; esac
      echo "[zap-baseline] $NAME: $t"
      zap-baseline.py -t "$t" -m "$MINS" $AJAX -I -c .zap/rules.tsv \
        -r "zap-baseline-$NAME.html" -J "zap-baseline-$NAME.json" -w "zap-baseline-$NAME.md" \
        -z "-config view.locale=en_GB"'
  local rc=$?
  # 0 = clean, 2 = warnings only (with -I warnings never fail); 1 = a FAIL rule fired; 3 = ZAP error
  if [ $rc -eq 1 ] || [ $rc -eq 3 ]; then status=1; fi
  echo "[zap-baseline] $1 finished with exit code $rc"
}

run frontend "${FRONTEND_URL:-auto-frontend}"
run gateway "$GATEWAY_URL/api/search/products?q=rice"
exit $status
