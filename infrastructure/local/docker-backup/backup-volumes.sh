#!/usr/bin/env bash
# =====================================================================================
# Docker volume backup: the app's persistent data, one archive per volume, plus each volume's metadata:
#
#   <backup>/volumes/<volume>-<timestamp>.tar.gz     the contents (ownership and permissions kept)
#   <backup>/volumes/<volume>-<timestamp>.json       docker volume inspect: name, driver, labels (to recreate it)
#
#   bash infrastructure/local/docker-backup/backup-volumes.sh <backup folder> [--restart]
#
# Which volumes:
#   - the Compose project's named volumes: grocery_mysql-data, grocery_kafka-data (also Debezium's connector offsets
#     and the Schema Registry's schemas), grocery_es-data, grocery_redis-data (carts), and any added later;
#   - the app's build-cache volumes (grocery-m2: Maven, including the app's own libraries in the versions the
#     services pin; grocery-web-linux-node-modules: the frontend's Linux node_modules).
# The anonymous volumes the images declare (Kafka Connect config/logs, secrets folders) hold no app data and are
# recreated empty by Docker; backup-all.sh lists them with their sizes in backup-information.txt.
#
# The app's running containers are stopped first, so MySQL, Kafka, Elasticsearch and Redis files are copied in a
# consistent state. They stay stopped unless --restart is given (the cleanup follows a backup).
# =====================================================================================
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

backup_dir="${1:?Usage: backup-volumes.sh <backup folder> [--restart]}"
restart=false
[[ "${2:-}" == "--restart" ]] && restart=true
stamp="${BACKUP_STAMP:-$(date +%Y%m%d-%H%M%S)}"
require_docker

mapfile -t volumes < <({ project_volumes; tooling_volumes; } | sed '/^$/d')
(( ${#volumes[@]} > 0 )) || fail "No volume of the '$PROJECT' project was found."
mkdir -p "$backup_dir/volumes"
target=$(host_path "$backup_dir/volumes")

mapfile -t running < <(running_project_containers | sed '/^$/d')
if (( ${#running[@]} > 0 )); then
  log "Stopping ${#running[@]} running containers of '$PROJECT' for a consistent copy ..."
  docker stop --time 60 "${running[@]}" >/dev/null
fi

for volume in "${volumes[@]}"; do
  docker volume inspect "$volume" > "$backup_dir/volumes/$volume-$stamp.json"
  log "Archiving volume $volume ..."
  # Read-only mount of the volume; the archive keeps numeric owners and permissions (MySQL, Elasticsearch need them).
  docker run --rm -v "$volume:/source:ro" -v "$target:/backup" "$HELPER_IMAGE" \
    tar -czf "/backup/$volume-$stamp.tar.gz" --numeric-owner -C /source .
  log "  $volume-$stamp.tar.gz ($(du -h "$backup_dir/volumes/$volume-$stamp.tar.gz" | cut -f1))"
done

if $restart && (( ${#running[@]} > 0 )); then
  log "Starting the containers again ..."
  docker start "${running[@]}" >/dev/null
fi
log "Volume backup: ${#volumes[@]} volumes."
