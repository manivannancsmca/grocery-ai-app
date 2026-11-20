#!/usr/bin/env bash
# =====================================================================================
# The complete backup of the app's Docker environment, in one command:
#
#   bash infrastructure/local/docker-backup/backup-all.sh [--backup-root <folder>] [--restart]
#
# Creates <backup root>/grocery-ai-app-backup-<timestamp>/ (default root: ../grocery-ai-app-backups, next to the
# repository):
#
#   database/        grocery-db-<timestamp>.sql                 every service database (backup-database.sh)
#   volumes/         <volume>-<timestamp>.tar.gz + .json        every app volume (backup-volumes.sh)
#   configuration/   compose, env, Dockerfiles, service config, infrastructure and source archives (backup-config.sh)
#   logs/            container-logs-<timestamp>.tar.gz          `docker logs` of every app container
#   scripts/         these scripts, so the backup restores without the repository
#   RESTORE-GUIDE.md                                            the step-by-step restore guide
#   backup-information-<timestamp>.txt                          what was backed up (no passwords)
#   SHA256SUMS, verification-<timestamp>.txt, VERIFIED          checksums, the verification report, its marker
#
# Order: configuration and the SQL dump while everything runs, then the logs, then the volumes with the containers
# stopped (consistent files). The containers stay stopped (a cleanup follows) unless --restart is given.
# Stops with an error, and without a VERIFIED marker, if any step or check fails.
# =====================================================================================
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

root="$DEFAULT_BACKUP_ROOT"
volume_args=()
while (( $# )); do
  case "$1" in
    --backup-root) root="${2:?--backup-root needs a folder}"; shift 2 ;;
    --restart)     volume_args+=(--restart); shift ;;
    *)             fail "Unknown option: $1 (use --backup-root <folder> or --restart)" ;;
  esac
done

require_docker
[[ -n "$(project_containers)" ]] || fail "The Docker Compose project '$PROJECT' has no containers: nothing to back up."

export BACKUP_STAMP
BACKUP_STAMP=$(date +%Y%m%d-%H%M%S)
dir="$root/grocery-ai-app-backup-$BACKUP_STAMP"
mkdir -p "$dir"/{database,volumes,configuration,logs,scripts}
dir="$(cd "$dir" && pwd)"
log "Backup folder: $(host_path "$dir")"

# What exists before anything changes (for the information file).
containers_before=$(docker ps -a --filter "label=com.docker.compose.project=$PROJECT" \
  --format '{{.Names}}|{{.Image}}|{{.State}}|{{.Ports}}' | sort)

log "== 1/6 Configuration"
bash "$SCRIPT_DIR/backup-config.sh" "$dir"

log "== 2/6 Database"
bash "$SCRIPT_DIR/backup-database.sh" "$dir"

log "== 3/6 Container logs"
mapfile -t containers < <(project_containers)
for container in "${containers[@]}"; do
  docker logs --timestamps "$container" > "$dir/logs/$container.log" 2>&1 || warn "No logs from $container"
done
tar -czf "$dir/logs/container-logs-$BACKUP_STAMP.tar.gz" -C "$dir/logs" --remove-files \
  $(cd "$dir/logs" && ls -1 ./*.log)
log "Logs of ${#containers[@]} containers: container-logs-$BACKUP_STAMP.tar.gz ($(du -h "$dir/logs/container-logs-$BACKUP_STAMP.tar.gz" | cut -f1))"

log "== 4/6 Volumes"
bash "$SCRIPT_DIR/backup-volumes.sh" "$dir" "${volume_args[@]}"

log "== 5/6 Information, scripts, guide, checksums"
cp -p "$SCRIPT_DIR"/*.sh "$SCRIPT_DIR/README.md" "$dir/scripts/"
cp -p "$REPO_ROOT/docs/operations/docker-backup-and-restore.md" "$dir/RESTORE-GUIDE.md"

info="$dir/backup-information-$BACKUP_STAMP.txt"
{
  echo "Grocery AI application: backup of the Docker environment"
  echo "========================================================"
  echo "Created:          $(date '+%Y-%m-%d %H:%M:%S %Z') (timestamp $BACKUP_STAMP)"
  echo "Compose project:  $PROJECT ($(host_path "$COMPOSE_DIR")/docker-compose.yml)"
  echo "Docker:           $(docker version --format '{{.Server.Version}}' 2>/dev/null) ($(docker info --format '{{.OperatingSystem}}' 2>/dev/null))"
  echo "Restore guide:    RESTORE-GUIDE.md in this folder"
  echo "No passwords are written in this file. configuration/env/.env holds the local development credentials."
  echo
  echo "Database (database/)"
  echo "--------------------"
  echo "  $(basename "$dir/database/grocery-db-$BACKUP_STAMP.sql")  $(du -h "$dir/database/grocery-db-$BACKUP_STAMP.sql" | cut -f1)"
  echo "  databases: $(sed -n 's/^-- Current Database: `\(.*\)`$/\1/p' "$dir/database/grocery-db-$BACKUP_STAMP.sql" | awk '!seen[$0]++' | tr '\n' ' ')"
  echo "  includes: tables, rows, indexes, constraints, routines, triggers, events. MySQL users: created by mysql-init."
  echo
  echo "Volumes (volumes/): restore with scripts/restore-volumes.sh"
  echo "-----------------------------------------------------------"
  for json in "$dir"/volumes/*-"$BACKUP_STAMP".json; do
    volume=$(basename "$json" "-$BACKUP_STAMP.json")
    # A build-cache volume has no Compose labels: grep then finds nothing (exit 1), which is fine here.
    labels=$(grep -oE '"com\.docker\.compose\.(project|volume)": "[^"]*"' "$json" | tr -d '"' | tr '\n' ' ' || true)
    printf '  %-34s archive %-8s %s\n' "$volume" "$(du -h "$dir/volumes/$volume-$BACKUP_STAMP.tar.gz" | cut -f1)" "${labels:-(no compose labels: a build cache)}"
  done
  echo
  echo "  Not backed up: the anonymous volumes the images declare (recreated empty by Docker, no app data):"
  for container in "${containers[@]}"; do
    docker inspect "$container" --format '{{range .Mounts}}{{if eq .Type "volume"}}{{.Name}} {{.Destination}}{{"\n"}}{{end}}{{end}}' 2>/dev/null \
      | while read -r name destination; do
          [[ -z "$name" || "$name" == "$PROJECT"_* ]] && continue
          printf '    %-22s %-28s %s\n' "${name:0:12}" "$container" "$destination"
        done
  done
  echo
  echo "Containers (state before the backup)"
  echo "------------------------------------"
  printf '%s\n' "$containers_before" | awk -F'|' '{ printf "  %-30s %-42s %-8s %s\n", $1, $2, $3, $4 }'
  echo
  echo "Images the app runs (compose pulls them again by these tags; digests pin the exact build)"
  echo "-----------------------------------------------------------------------------------------"
  docker ps -a --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Image}}' | sort -u | while read -r image; do
    printf '  %-45s %s\n' "$image" "$(docker image inspect --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{end}}' "$image" 2>/dev/null)"
  done
  echo
  echo "Networks"
  echo "--------"
  for network in $(project_networks); do
    printf '  %-20s %s (recreated by docker compose up)\n' "$network" \
      "$(docker network inspect "$network" --format '{{.Driver}} {{range .IPAM.Config}}{{.Subnet}}{{end}}')"
  done
  echo
  echo "Configuration (configuration/)"
  echo "------------------------------"
  (cd "$dir/configuration" && find . -maxdepth 1 -mindepth 1 | sort | sed 's|^\./|  |')
} > "$info"

# Checksums of every file (the verification and a later restore compare against them).
(cd "$dir" && find . -type f ! -name SHA256SUMS ! -name VERIFIED ! -name 'verification-*' -print0 \
  | sort -z | xargs -0 sha256sum > SHA256SUMS)
log "$(wc -l < "$dir/SHA256SUMS") files, $(du -sh "$dir" | cut -f1) in total"

log "== 6/6 Verification"
bash "$SCRIPT_DIR/verify-backup.sh" "$dir"
log "Backup complete and verified: $(host_path "$dir")"
