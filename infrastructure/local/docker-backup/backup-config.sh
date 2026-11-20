#!/usr/bin/env bash
# =====================================================================================
# Configuration backup: what is needed to run the app again, besides its data.
#
#   <backup>/configuration/compose/                 docker-compose*.yml of infrastructure/local
#   <backup>/configuration/env/                     infrastructure/local/.env (local credentials!) and .env.example
#   <backup>/configuration/dockerfiles/             every Dockerfile, at its path in the repository
#   <backup>/configuration/application-config/      each service's application*.yml and Debezium connector JSON
#   <backup>/configuration/infrastructure-local-<timestamp>.tar.gz   infrastructure/local (init SQL, Kafka, Debezium,
#                                                   Schema Registry, observability, host-run scripts), without backups/
#   <backup>/configuration/source-code-<timestamp>.tar.gz   the whole repository without generated folders
#                                                   (node_modules, target, dist, coverage, test results, backups):
#                                                   this folder is not a git repository, so this is the code's copy.
#
#   bash infrastructure/local/docker-backup/backup-config.sh <backup folder>
#
# Docker is not needed. The .env file holds the local development credentials: keep the backup folder private.
# =====================================================================================
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

backup_dir="${1:?Usage: backup-config.sh <backup folder>}"
stamp="${BACKUP_STAMP:-$(date +%Y%m%d-%H%M%S)}"
config="$backup_dir/configuration"
mkdir -p "$config/compose" "$config/env" "$config/dockerfiles" "$config/application-config"

# Copies a file to the same relative path under a folder of the backup.
keep() {
  local relative="$1" into="$2"
  mkdir -p "$into/$(dirname "$relative")"
  cp -p "$REPO_ROOT/$relative" "$into/$relative"
}

cd "$REPO_ROOT"

cp -p infrastructure/local/docker-compose*.yml "$config/compose/"
for env in infrastructure/local/.env infrastructure/local/.env.example; do
  [[ -f "$env" ]] && cp -p "$env" "$config/env/"
done
[[ -f "$config/env/.env" ]] || warn "infrastructure/local/.env does not exist (the defaults in .env.example apply)."

count=0
while IFS= read -r -d '' file; do
  keep "${file#./}" "$config/dockerfiles"
  count=$((count + 1))
done < <(find . \( -name node_modules -o -name target -o -name dist \) -prune -o -type f -name 'Dockerfile*' -print0)
log "Dockerfiles: $count"

count=0
while IFS= read -r -d '' file; do
  keep "${file#./}" "$config/application-config"
  count=$((count + 1))
done < <(find ./services -path '*/target' -prune -o -type f \
  \( -path '*/src/main/resources/application*.yml' -o -path '*/deploy/*.json' \) -print0)
log "Service configuration files: $count"

tar -czf "$config/infrastructure-local-$stamp.tar.gz" --exclude='./backups' -C infrastructure/local .
log "infrastructure/local: infrastructure-local-$stamp.tar.gz ($(du -h "$config/infrastructure-local-$stamp.tar.gz" | cut -f1))"

log "Archiving the source code (without generated folders) ..."
# GNU tar exits with 1 when a file changed while it was read (an editor saving, say): the archive is still complete
# and readable, so that is a warning, not a failure. Anything above 1 is a real error.
status=0
tar -czf "$config/source-code-$stamp.tar.gz" \
  --exclude='node_modules' --exclude='target' --exclude='dist' --exclude='coverage' \
  --exclude='test-results' --exclude='playwright-report' --exclude='./infrastructure/local/backups' \
  -C "$REPO_ROOT" . || status=$?
(( status <= 1 )) || fail "Archiving the source code failed (tar exit $status)."
(( status == 0 )) || warn "A file changed while the source code was archived (tar exit 1); the archive is complete."
log "Source code: source-code-$stamp.tar.gz ($(du -h "$config/source-code-$stamp.tar.gz" | cut -f1))"
