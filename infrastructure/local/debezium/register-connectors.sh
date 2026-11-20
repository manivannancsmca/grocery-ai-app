#!/bin/bash
# Registers (create-or-update) every service's Debezium outbox connector.
# Each service owns its connector config at services/<service>/deploy/debezium-connector.json,
# so adding a service never requires editing this script. Runs in a one-shot "connect-init" container.
set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://kafka-connect:8083}"
SERVICES_DIR="${SERVICES_DIR:-/services}"

echo "Waiting for Kafka Connect at ${CONNECT_URL} ..."
until curl -sf "${CONNECT_URL}/connectors" >/dev/null; do sleep 3; done

shopt -s nullglob
files=("${SERVICES_DIR}"/*/deploy/debezium-connector.json)
if [ ${#files[@]} -eq 0 ]; then
  echo "No connector configs found under ${SERVICES_DIR}"; exit 0
fi

for file in "${files[@]}"; do
  # The config file is a flat JSON map that includes "name".
  name=$(sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$file" | head -1)
  if [ -z "$name" ]; then echo "Skipping $file (no \"name\")"; continue; fi
  echo "Registering connector '${name}' from ${file}"
  # PUT /connectors/{name}/config is idempotent: creates or updates.
  status=$(curl -s -o /tmp/resp.json -w '%{http_code}' -X PUT \
           -H 'Content-Type: application/json' --data @"$file" \
           "${CONNECT_URL}/connectors/${name}/config")
  if [ "$status" != "200" ] && [ "$status" != "201" ]; then
    echo "Failed (${status}):"; cat /tmp/resp.json; echo; exit 1
  fi
done

sleep 3
for file in "${files[@]}"; do
  name=$(sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$file" | head -1)
  echo "${name}: $(curl -s "${CONNECT_URL}/connectors/${name}/status")"
done
