#!/usr/bin/env bash
# =====================================================================================
# Docker volume restore: recreates the app's volumes from a backup, with their original names and labels, and puts
# the backed-up contents back (owners and permissions kept).
#
#   bash restore-volumes.sh <backup folder> [--volume <name>]... [--replace]
#
#   --volume <name>   restore only this volume (repeatable); default: every volume in the backup
#   --replace         a volume that already exists is removed and restored (its current contents are lost);
#                     without it, an existing volume stops the restore before anything changes
#
# The Compose labels (project, volume, config hash) are recreated too, so `docker compose up` adopts the volumes as
# its own. Start the containers only afterwards. Works from the repository (infrastructure/local/docker-backup) or
# from the backup's own scripts/ folder.
# =====================================================================================
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

backup_dir="" replace=false
selected=()
while (( $# )); do
  case "$1" in
    --volume)  selected+=("${2:?--volume needs a volume name}"); shift 2 ;;
    --replace) replace=true; shift ;;
    -*)        fail "Unknown option: $1" ;;
    *)         backup_dir="$1"; shift ;;
  esac
done
[[ -n "$backup_dir" && -d "$backup_dir" ]] || fail "Usage: restore-volumes.sh <backup folder> [--volume <name>]... [--replace]"
backup_dir="$(cd "$backup_dir" && pwd)"
stamp=$(basename "$backup_dir" | sed -n 's/^grocery-ai-app-backup-//p')
[[ -n "$stamp" ]] || fail "$backup_dir is not a folder made by backup-all.sh."
require_docker

if (( ${#selected[@]} == 0 )); then
  mapfile -t selected < <(ls -1 "$backup_dir/volumes" | sed -n "s/-$stamp\.json$//p")
fi
(( ${#selected[@]} > 0 )) || fail "The backup has no volumes."

# Check everything first: nothing changes unless every volume can be restored.
for volume in "${selected[@]}"; do
  [[ -s "$backup_dir/volumes/$volume-$stamp.tar.gz" ]] || fail "No archive for $volume in the backup."
  [[ -s "$backup_dir/volumes/$volume-$stamp.json" ]] || fail "No metadata for $volume in the backup."
  if docker volume inspect "$volume" >/dev/null 2>&1; then
    $replace || fail "Volume $volume already exists. Use --replace to overwrite it (its current data is lost)."
    users=$(docker ps -a --filter volume="$volume" --format '{{.Names}}' | tr '\n' ' ')
    [[ -z "$users" ]] || fail "Volume $volume is used by containers ($users): remove them first (docker compose down)."
  fi
done

target=$(host_path "$backup_dir/volumes")
for volume in "${selected[@]}"; do
  json="$backup_dir/volumes/$volume-$stamp.json"
  if docker volume inspect "$volume" >/dev/null 2>&1; then
    log "Removing the existing volume $volume (--replace)"
    docker volume rm "$volume" >/dev/null
  fi
  # The labels block of `docker volume inspect` → --label key=value for each.
  labels=()
  while IFS= read -r label; do
    [[ -n "$label" ]] && labels+=(--label "$label")
  done < <(awk '/"Labels": \{/ { inside = 1; next } inside && /\}/ { inside = 0 } inside' "$json" \
             | sed -E 's/^[[:space:]]*"([^"]+)": "(.*)",?[[:space:]]*$/\1=\2/')
  driver=$(grep -oE '"Driver": "[^"]+"' "$json" | head -n 1 | cut -d'"' -f4)
  docker volume create --driver "${driver:-local}" "${labels[@]}" "$volume" >/dev/null
  log "Restoring $volume ($(du -h "$backup_dir/volumes/$volume-$stamp.tar.gz" | cut -f1)) ..."
  docker run --rm -v "$volume:/target" -v "$target:/backup:ro" "$HELPER_IMAGE" \
    tar -xzf "/backup/$volume-$stamp.tar.gz" --numeric-owner -C /target
done
log "Restored ${#selected[@]} volumes. Next: start the application (RESTORE-GUIDE.md, step 8)."
