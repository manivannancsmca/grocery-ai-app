#!/bin/bash
# =====================================================================================
# Debezium connector watchdog (Phase 10). Runs forever in the "connect-watchdog" container.
#
# Every CHECK_INTERVAL seconds it checks that every connector AND every task is RUNNING. A connector can report
# RUNNING while its task is FAILED — then no events flow at all (see ADR-0002, Phase 9 incident). A FAILED task is
# restarted (POST /connectors/<name>/restart?includeTasks=true&onlyFailed=true), at most MAX_RESTARTS times per
# connector within RESTART_WINDOW seconds; after that it is left FAILED (visible on the admin System status page) so a
# permanent error (bad config, unparseable data) is not restarted forever. Every action is logged.
# Production: the same check belongs in monitoring/alerting (MSK Connect or the Connect REST API).
# =====================================================================================
set -uo pipefail

CONNECT="${CONNECT_URL:-http://kafka-connect:8083}"
INTERVAL="${CHECK_INTERVAL:-30}"
MAX_RESTARTS="${MAX_RESTARTS:-3}"
WINDOW="${RESTART_WINDOW:-600}"
declare -A HISTORY=()   # connector -> epoch seconds of its recent restarts

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*"; }

log "connect-watchdog: checking ${CONNECT} every ${INTERVAL}s (at most ${MAX_RESTARTS} restarts per connector per ${WINDOW}s)"
until curl -sf "${CONNECT}/connectors" >/dev/null; do sleep 5; done

while true; do
  now=$(date +%s)
  names=$(curl -sf "${CONNECT}/connectors" | tr -d '[]"' | tr ',' ' ')
  for name in $names; do
    status=$(curl -sf "${CONNECT}/connectors/${name}/status") || { log "WARN ${name}: status not readable"; continue; }
    cstate=$(echo "$status" | grep -o '"connector":{"state":"[A-Z]*"' | grep -o '[A-Z]*"$' | tr -d '"')
    tasks=$(echo "$status" | grep -o '"tasks":\[.*' | grep -o '"state":"[A-Z]*"' | cut -d'"' -f4 | tr '\n' ' ')
    if [ "$cstate" != FAILED ] && ! echo "$tasks" | grep -q FAILED; then continue; fi
    recent=""; for t in ${HISTORY[$name]:-}; do [ $((now - t)) -lt "$WINDOW" ] && recent="$recent $t"; done
    count=$(echo "$recent" | wc -w)
    if [ "$count" -ge "$MAX_RESTARTS" ]; then
      HISTORY[$name]="$recent"
      log "ERROR ${name}: still FAILED after ${count} restarts in ${WINDOW}s; left for an operator (connector=${cstate} tasks=${tasks})"
      continue
    fi
    code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${CONNECT}/connectors/${name}/restart?includeTasks=true&onlyFailed=true")
    HISTORY[$name]="$recent $now"
    log "WARN ${name}: connector=${cstate} tasks=${tasks}-> restarted the failed tasks (HTTP ${code}; restart $((count + 1)) of ${MAX_RESTARTS})"
  done
  sleep "$INTERVAL"
done
