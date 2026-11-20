#!/usr/bin/env bash
# Stages the build context of the grocery-platform-init image (see Dockerfile next to this script).
#   infrastructure/k8s/platform-init/stage.sh <output-dir>      (run from the repository root)
# The same files the local Compose setup mounts are copied, so Kubernetes and Compose never drift apart.
set -euo pipefail

out="${1:?usage: stage.sh <output-dir>}"
rm -rf "$out"
bundle="$out/bundle"
mkdir -p "$bundle"/{mysql,kafka,schema-registry,schemas,debezium,services}

cp infrastructure/local/mysql/init/*.sql                  "$bundle/mysql/"
cp infrastructure/local/kafka/create-topics.sh            "$bundle/kafka/"
cp infrastructure/local/schema-registry/register-schemas.sh "$bundle/schema-registry/"
cp -R libs/grocery-event-schemas/src/main/avro/.          "$bundle/schemas/"
cp infrastructure/local/debezium/register-connectors.sh   "$bundle/debezium/"

count=0
for connector in services/*/deploy/debezium-connector.json; do
  service="$(basename "$(dirname "$(dirname "$connector")")")"
  mkdir -p "$bundle/services/$service/deploy"
  cp "$connector" "$bundle/services/$service/deploy/"
  count=$((count + 1))
done

echo "Staged $(find "$bundle" -type f | wc -l) files into $out ($count Debezium connectors)"
