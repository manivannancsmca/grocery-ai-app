#!/usr/bin/env bash
# Checks that every Debezium connector AND its task are RUNNING (a connector can be RUNNING with a FAILED task, and
# then no events flow). Exit code 0 = healthy. Restart failed tasks with:
#   curl -X POST "localhost:8083/connectors/<name>/restart?includeTasks=true&onlyFailed=true"
CONNECT="${CONNECT_URL:-http://localhost:8083}"
bad=0
for c in $(curl -sf "$CONNECT/connectors" | tr -d '[]"' | tr ',' ' '); do
  s=$(curl -sf "$CONNECT/connectors/$c/status")
  conn=$(echo "$s" | grep -o '"connector":{"state":"[A-Z]*"' | grep -o '[A-Z]*"$' | tr -d '"')
  tasks=$(echo "$s" | grep -o '"tasks":\[.*' | grep -o '"state":"[A-Z]*"' | cut -d'"' -f4 | tr '\n' ' ')
  if [ "$conn" = RUNNING ] && ! echo "$tasks" | grep -qv RUNNING; then echo "  ✔ $c: $conn / tasks: $tasks"
  else echo "  ✘ $c: connector $conn / tasks: ${tasks:-none}"; bad=1; fi
done
exit $bad
