#!/usr/bin/env bash
# =====================================================================================================================
# run-pipeline.sh - triggers a job of the local validation Jenkins and follows it to the end.
#
#   bash infrastructure/ci/jenkins/local/run-pipeline.sh services/pricing-service --approve
#   bash infrastructure/ci/jenkins/local/run-pipeline.sh frontend/grocery-web --approve
#   bash infrastructure/ci/jenkins/local/run-pipeline.sh libs/grocery-event-schemas
#   bash infrastructure/ci/jenkins/local/run-pipeline.sh services/order-service VALIDATE_ONLY=true
#   bash infrastructure/ci/jenkins/local/run-pipeline.sh seed-jobs        # re-read the Jenkinsfiles
#
#   <job>          folder/name as in Jenkins (services/<svc>, libs/<lib>, frontend/<app>, platform/<x>, seed-jobs)
#   NAME=VALUE     build parameters (DRY_RUN is always true in the local Jenkins)
#   --approve      answer the manual approval gates (staging, prod) with "proceed" when they open; without it
#                  the run waits there (approve or abort in the UI)
# Prints the stages (status, duration) and the result; exit 0 when the result is SUCCESS.
# Environment: JENKINS_URL (http://localhost:8190), JENKINS_ADMIN_ID (admin), JENKINS_ADMIN_PASSWORD (required),
#              JENKINS_CONTAINER (grocery-ci-jenkins), OUT_DIR (save consoleText + stages JSON there), QUIET=1
# =====================================================================================================================
set -uo pipefail
[ $# -ge 1 ] || { sed -n '4,17p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
JOB="$1"; shift
URL="${JENKINS_URL:-http://localhost:8190}"
USER_ID="${JENKINS_ADMIN_ID:-admin}"
PASS="${JENKINS_ADMIN_PASSWORD:?set JENKINS_ADMIN_PASSWORD}"
CONTAINER="${JENKINS_CONTAINER:-grocery-ci-jenkins}"
APPROVE=0
PARAMS=()
for a in "$@"; do
  case "$a" in
    --approve) APPROVE=1 ;;
    *=*) PARAMS+=("$a") ;;
    *) echo "unknown argument: $a" >&2; exit 2 ;;
  esac
done

say() { [ "${QUIET:-0}" = "1" ] || printf '[run-pipeline] %s\n' "$*"; }
PY=""
for p in python python3; do
  if command -v "$p" >/dev/null 2>&1 && "$p" -c 'import json' >/dev/null 2>&1; then PY="$p"; break; fi
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
JAR="$WORK/cookies"
JOB_PATH="/job/${JOB//\//\/job\/}"
api() { curl -sS -u "$USER_ID:$PASS" -b "$JAR" "$URL$1"; }
CRUMB="$(curl -fsS -u "$USER_ID:$PASS" -c "$JAR" "$URL/crumbIssuer/api/json" | sed -n 's/.*"crumb":"\([^"]*\)".*/\1/p')"
[ -n "$CRUMB" ] || { echo "run-pipeline: cannot reach $URL"; exit 99; }
post() { curl -sS -u "$USER_ID:$PASS" -b "$JAR" -H "Jenkins-Crumb: $CRUMB" -X POST "$@"; }

if docker inspect "$CONTAINER" >/dev/null 2>&1; then
  docker exec "$CONTAINER" sync-shared-library.sh >/dev/null 2>&1 && say "shared library re-synced"
fi

# ------------------------------------------------------------------------------------------------------- trigger
if [ ${#PARAMS[@]} -gt 0 ] || [ "$JOB" != "seed-jobs" ]; then
  args=()
  for p in "${PARAMS[@]+"${PARAMS[@]}"}"; do args+=(--data-urlencode "$p"); done
  [ ${#args[@]} -gt 0 ] || args=(--data "DRY_RUN=true")
  headers="$(post -D - -o /dev/null "${args[@]}" "$URL$JOB_PATH/buildWithParameters")"
else
  headers="$(post -D - -o /dev/null "$URL$JOB_PATH/build")"
fi
QUEUE="$(printf '%s' "$headers" | tr -d '\r' | sed -n 's/^[Ll]ocation: *//p' | head -1)"
[ -n "$QUEUE" ] || { echo "run-pipeline: trigger of $JOB failed:"; printf '%s\n' "$headers" | head -5; exit 1; }
QUEUE_PATH="${QUEUE#"$URL"}"
QUEUE_PATH="/${QUEUE_PATH#*://*/}"
NUMBER=""
for _ in $(seq 1 120); do
  NUMBER="$(api "${QUEUE_PATH%/}/api/json" | sed -n 's/.*"executable":{[^}]*"number":\([0-9]*\).*/\1/p')"
  [ -n "$NUMBER" ] && break
  sleep 2
done
[ -n "$NUMBER" ] || { echo "run-pipeline: $JOB did not leave the queue"; exit 1; }
BUILD="$JOB_PATH/$NUMBER"
say "$JOB #$NUMBER started: $URL$BUILD/"
START=$(date +%s)

# -------------------------------------------------------------------------------------------------------- follow
last_stage=""
while :; do
  state="$(api "$BUILD/api/json?tree=building,result")"
  if printf '%s' "$state" | grep -q '"building":false'; then break; fi
  if [ "$APPROVE" = "1" ]; then
    for id in $(api "$BUILD/wfapi/pendingInputActions" | grep -o '"id":"[^"]*"' | cut -d'"' -f4); do
      say "approval gate '$id' open -> proceed"
      post -o /dev/null "$URL$BUILD/input/$id/proceedEmpty"
    done
  fi
  if [ -n "$PY" ] && [ "${QUIET:-0}" != "1" ]; then
    stage="$(api "$BUILD/wfapi/describe" | "$PY" -c 'import json,sys
d=json.load(sys.stdin); s=[x for x in d.get("stages",[]) if x.get("status")=="IN_PROGRESS"]
print(s[-1]["name"] if s else "")' 2>/dev/null)"
    if [ -n "$stage" ] && [ "$stage" != "$last_stage" ]; then say "  ... $stage ($(( $(date +%s) - START )) s)"; last_stage="$stage"; fi
  fi
  sleep 5
done

# -------------------------------------------------------------------------------------------------------- report
RESULT="$(api "$BUILD/api/json?tree=result" | sed -n 's/.*"result":"\([A-Z_]*\)".*/\1/p')"
ELAPSED=$(( $(date +%s) - START ))
if [ -n "${OUT_DIR:-}" ]; then
  mkdir -p "$OUT_DIR"
  safe="$(echo "$JOB" | tr '/' '_')-$NUMBER"
  api "$BUILD/consoleText" > "$OUT_DIR/$safe.log"
  api "$BUILD/wfapi/describe" > "$OUT_DIR/$safe-stages.json"
  say "console log: $OUT_DIR/$safe.log"
fi
if [ -n "$PY" ] && [ "${QUIET:-0}" != "1" ]; then
  api "$BUILD/wfapi/describe" | "$PY" -c 'import json,sys
d=json.load(sys.stdin)
for s in d.get("stages",[]):
    print("  %-36s %-12s %7.1f s" % (s["name"][:36], s["status"], s.get("durationMillis",0)/1000.0))'
fi
echo "$JOB #$NUMBER: ${RESULT:-UNKNOWN} in ${ELAPSED}s"
[ "$RESULT" = "SUCCESS" ]
