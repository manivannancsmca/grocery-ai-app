#!/usr/bin/env bash
# =====================================================================================================================
# Decides what this CI run builds, from the files the push / pull request changed.
#
# Outputs (written to $GITHUB_OUTPUT):
#   services       JSON array of changed services: [{"name":"order-service","port":"8110"}, ...]
#   images         JSON array of every image to publish: services + "grocery-web-ui" + "platform-init"
#   any_service    true when at least one service is in the list
#   frontend       true when frontend/grocery-web-ui changed
#   platform_init  true when the Kubernetes setup data changed (SQL, topics, schemas, connectors)
#
# Rules
#   - services/<svc>/** changed                       -> that service
#   - libs/**, infrastructure/ci/docker/Dockerfile.service, .github/** changed, or BUILD_ALL=true -> every service
#   - frontend/grocery-web-ui/**                      -> the frontend
#   - schemas, SQL, topic/connector scripts, services/*/deploy/*.json, infrastructure/k8s/platform-init/**
#                                                     -> the platform-init bundle
# Every service is a directory under services/ with a Dockerfile; its port is the Dockerfile's EXPOSE line.
#
# Environment: BASE_SHA (commit to compare with; empty/zeros = first push), HEAD_SHA, BUILD_ALL (true/false).
# =====================================================================================================================
set -euo pipefail

head_sha="${HEAD_SHA:-HEAD}"
base_sha="${BASE_SHA:-}"
build_all="${BUILD_ALL:-false}"

if [[ -z "$base_sha" || "$base_sha" =~ ^0+$ ]] || ! git cat-file -e "${base_sha}^{commit}" 2>/dev/null; then
  echo "No usable base commit ('${base_sha}'): building everything."
  build_all=true
  changed=""
else
  changed="$(git diff --name-only "$base_sha" "$head_sha")"
fi

echo "Changed files:"; echo "${changed:-  (none / full build)}" | sed 's/^/  /'

matches() { grep -qE "$1" <<<"$changed"; }

all_services=()
for dockerfile in services/*/Dockerfile; do
  all_services+=("$(basename "$(dirname "$dockerfile")")")
done

if [[ "$build_all" != "true" ]] && matches '^(libs/|infrastructure/ci/docker/Dockerfile\.service|\.github/)'; then
  echo "Shared code, the runtime Dockerfile or CI changed: every service is rebuilt."
  build_all=true
fi

selected=()
for service in "${all_services[@]}"; do
  if [[ "$build_all" == "true" ]] || matches "^services/${service}/"; then
    selected+=("$service")
  fi
done

frontend=false
if [[ "$build_all" == "true" ]] || matches '^frontend/grocery-web-ui/'; then frontend=true; fi

platform_init=false
if [[ "$build_all" == "true" ]] || matches '^(infrastructure/local/(mysql/init|kafka|debezium|schema-registry)/|libs/grocery-event-schemas/src/main/avro/|services/[^/]+/deploy/debezium-connector\.json|infrastructure/k8s/platform-init/)'; then
  platform_init=true
fi

services_json="$(
  for service in "${selected[@]}"; do
    port="$(awk '/^EXPOSE/ {print $2; exit}' "services/${service}/Dockerfile")"
    printf '{"name":"%s","port":"%s"}\n' "$service" "${port:-8080}"
  done | jq -sc '.'
)"
images_json="$(
  {
    jq -c '.[] | {name: .name, kind: "service", port: .port}' <<<"$services_json"
    if [[ "$frontend" == "true" ]]; then echo '{"name":"grocery-web-ui","kind":"frontend","port":"8080"}'; fi
    if [[ "$platform_init" == "true" ]]; then echo '{"name":"platform-init","kind":"platform-init","port":""}'; fi
  } | jq -sc '.'
)"

any_service=false
if [[ "${#selected[@]}" -gt 0 ]]; then any_service=true; fi

{
  echo "services=${services_json}"
  echo "images=${images_json}"
  echo "any_service=${any_service}"
  echo "frontend=${frontend}"
  echo "platform_init=${platform_init}"
} >> "${GITHUB_OUTPUT:-/dev/stdout}"

echo "Services (${#selected[@]}): ${selected[*]:-none}"
echo "Frontend: ${frontend}   platform-init: ${platform_init}"
