#!/bin/bash
# =====================================================================================
# Registers every central Avro event schema in the Confluent Schema Registry.
#
# Source of truth: libs/grocery-event-schemas/src/main/avro/<topic>/<EventType>.avsc
#   subject = <topic>-com.grocery.events.<domain>.<EventType>   (TopicRecordNameStrategy;
#             <domain> = topic before the first dot; enforced by the module's tests)
#   eventVersion n>=2 files are <EventType>.v<n>.avsc with record name <EventType>V<n>.
#
# For every schema, in this order:
#   1. pins the subject's compatibility to ${SCHEMA_COMPATIBILITY} (BACKWARD_TRANSITIVE), so a later change of the
#      registry's global default can never weaken it;
#   2. if the subject already has versions: asks the registry whether the schema is compatible with ALL of them
#      (verbose) and stops with the registry's explanation when it is not — nothing incompatible is ever registered;
#   3. registers it (idempotent: an identical schema returns its existing id and creates no new version).
#
# Services never register schemas themselves (auto.register.schemas=false); this script is the only writer, here
# in a one-shot container ("schema-init") and in CI before a deployment. Exit code 0 = every schema registered.
# Needs only bash, curl, sed and tr (runs in the Debezium Connect image that connect-init already uses).
# =====================================================================================
set -uo pipefail

SR="${SCHEMA_REGISTRY_URL:-http://schema-registry:8081}"
DIR="${SCHEMAS_DIR:-/schemas}"
COMPAT="${SCHEMA_COMPATIBILITY:-BACKWARD_TRANSITIVE}"
CT='Content-Type: application/vnd.schemaregistry.v1+json'

echo "Waiting for the Schema Registry at ${SR} ..."
until curl -sf "${SR}/subjects" >/dev/null; do sleep 2; done

shopt -s nullglob
files=("${DIR}"/*/*.avsc)
if [ ${#files[@]} -eq 0 ]; then
  echo "No schemas found under ${DIR}"; exit 1
fi

# Global default for subjects created by anyone else (the per-subject setting below is what protects ours).
curl -sf -X PUT -H "$CT" --data "{\"compatibility\":\"${COMPAT}\"}" "${SR}/config" >/dev/null \
  || { echo "Could not set the global compatibility level"; exit 1; }

registered=0; unchanged=0; failed=0
for file in "${files[@]}"; do
  topic=$(basename "$(dirname "$file")")
  base=$(basename "$file" .avsc)                       # OrderCreated | OrderCreated.v2
  if [[ "$base" =~ ^(.+)\.v([0-9]+)$ ]]; then name="${BASH_REMATCH[1]}V${BASH_REMATCH[2]}"; else name="$base"; fi
  subject="${topic}-com.grocery.events.${topic%%.*}.${name}"
  # The registry wants the schema as a JSON string: drop line breaks, escape backslashes and quotes.
  schema=$(tr -d '\r\n' < "$file" | sed 's/\\/\\\\/g; s/"/\\"/g')
  body="{\"schemaType\":\"AVRO\",\"schema\":\"${schema}\"}"

  if ! curl -sf -X PUT -H "$CT" --data "{\"compatibility\":\"${COMPAT}\"}" "${SR}/config/${subject}" >/dev/null; then
    echo "  ✘ ${subject}: could not set compatibility"; failed=$((failed+1)); continue
  fi

  if curl -sf "${SR}/subjects/${subject}/versions" >/dev/null; then
    check=$(curl -s -X POST -H "$CT" --data "$body" "${SR}/compatibility/subjects/${subject}/versions?verbose=true")
    if ! echo "$check" | grep -q '"is_compatible":true'; then
      echo "  ✘ ${subject}: NOT ${COMPAT}-compatible with the registered versions — not registered"
      echo "    ${check}"
      failed=$((failed+1)); continue
    fi
  fi

  before=$(curl -sf "${SR}/subjects/${subject}/versions" | tr -d '[] ' | tr ',' '\n' | grep -c . || true)
  resp=$(curl -s -w '\n%{http_code}' -X POST -H "$CT" --data "$body" "${SR}/subjects/${subject}/versions")
  code=$(echo "$resp" | tail -1); json=$(echo "$resp" | head -n -1)
  if [ "$code" != "200" ]; then
    echo "  ✘ ${subject}: registration failed (${code}) ${json}"; failed=$((failed+1)); continue
  fi
  id=$(echo "$json" | grep -o '"id":[0-9]*' | cut -d: -f2)
  after=$(curl -sf "${SR}/subjects/${subject}/versions" | tr -d '[] ' | tr ',' '\n' | grep -c . || true)
  if [ "$after" -gt "$before" ]; then
    echo "  ✔ ${subject} → schema id ${id} (version ${after}, new)"; registered=$((registered+1))
  else
    echo "  ✔ ${subject} → schema id ${id} (version ${after}, unchanged)"; unchanged=$((unchanged+1))
  fi
done

echo "Schemas: ${registered} new, ${unchanged} unchanged, ${failed} failed (compatibility ${COMPAT})"
[ "$failed" -eq 0 ]
