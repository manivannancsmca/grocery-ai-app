#!/usr/bin/env bash
# =====================================================================================
# Removes ONLY the app's Docker resources, and only after a verified backup.
#
#   bash infrastructure/local/docker-backup/cleanup-grocery-docker.sh <backup folder> [options]
#
#   --dry-run                  show what would be removed, remove nothing
#   --yes                      do not ask (otherwise you type the project name to confirm)
#   --include-tooling-images   also remove the app's build/test tool images (Maven + JDK 25, Node 24)
#   --extra-volume <name>      also remove this volume (e.g. a leftover test volume); repeatable. Refused when a
#                              container outside the project uses it.
#   --accept-older-backup      remove even though an app container is running or ran after the backup (whatever
#                              changed since the backup is lost)
#
# What it removes (the plan is printed first):
#   - containers of the Compose project (label com.docker.compose.project=grocery), with their anonymous volumes;
#   - the project's networks (grocery_default);
#   - the project's named volumes (grocery_mysql-data, ...) and the app's build-cache volumes (grocery-*);
#   - images that only the project's containers use (never one that another project's container uses), and images
#     Compose built for the project;
#   - the containers' logs (they go with the containers; backup-all.sh saved them).
# What it never does: global commands (docker system/image/volume/builder prune), anything of another project.
# Docker's BuildKit build cache is shared by every project, so it is left alone (the app builds no images here).
#
# Safety: the backup folder must have the VERIFIED marker, its files must still match SHA256SUMS, every volume about
# to be removed must have an archive in it, and no app container may run now or have started after the backup (its
# data could be newer). Otherwise nothing is removed.
# =====================================================================================
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

TOOLING_IMAGES=("maven:3.9.16-eclipse-temurin-25-noble" "node:24.22.2-alpine3.23")

backup_dir="" dry_run=false yes=false tooling=false accept_older=false
extra_volumes=()
while (( $# )); do
  case "$1" in
    --dry-run)                dry_run=true; shift ;;
    --accept-older-backup)    accept_older=true; shift ;;
    --yes)                    yes=true; shift ;;
    --include-tooling-images) tooling=true; shift ;;
    --extra-volume)           extra_volumes+=("${2:?--extra-volume needs a volume name}"); shift 2 ;;
    -*)                       fail "Unknown option: $1" ;;
    *)                        backup_dir="$1"; shift ;;
  esac
done
[[ -n "$backup_dir" ]] || fail "Usage: cleanup-grocery-docker.sh <backup folder> [--dry-run] [--yes] [--include-tooling-images] [--extra-volume <name>]"
require_docker

# ---- 1. a verified backup ------------------------------------------------------------------
[[ -d "$backup_dir" ]] || fail "No such backup folder: $backup_dir. Nothing was removed."
backup_dir="$(cd "$backup_dir" && pwd)"
stamp=$(basename "$backup_dir" | sed -n 's/^grocery-ai-app-backup-//p')
[[ -f "$backup_dir/VERIFIED" ]] || fail "The backup has no VERIFIED marker (run verify-backup.sh). Nothing was removed."
(cd "$backup_dir" && sha256sum --quiet -c SHA256SUMS >/dev/null 2>&1) \
  || fail "The backup's files no longer match SHA256SUMS. Nothing was removed."
log "Backup: $(host_path "$backup_dir") ($(cat "$backup_dir/VERIFIED"))"

# ---- 2. the plan -----------------------------------------------------------------------------
mapfile -t containers < <(project_containers | sed '/^$/d')
mapfile -t networks < <(project_networks | sed '/^$/d')
mapfile -t volumes < <({ project_volumes; tooling_volumes; } | sed '/^$/d')
for volume in "${volumes[@]}"; do
  [[ -f "$backup_dir/volumes/$volume-$stamp.tar.gz" ]] \
    || fail "Volume $volume is not in this backup (made after it?). Back up again first. Nothing was removed."
done

# The data must not have changed since the backup: no container of the app may run now, or have started after it.
if ! $accept_older; then
  backup_epoch=$(date -d "${stamp:0:4}-${stamp:4:2}-${stamp:6:2} ${stamp:9:2}:${stamp:11:2}:${stamp:13:2}" +%s)
  for container in "${containers[@]}"; do
    if [[ "$(docker inspect -f '{{.State.Running}}' "$container")" == true ]]; then
      fail "$container is running, so its data may be newer than the backup. Back up again (backup-all.sh stops the containers), or add --accept-older-backup. Nothing was removed."
    fi
    started=$(date -d "$(docker inspect -f '{{.State.StartedAt}}' "$container")" +%s 2>/dev/null || echo 0)
    if (( started > backup_epoch )); then
      fail "$container ran after the backup ($(date -d "@$started" '+%Y-%m-%d %H:%M')), so its data may be newer. Back up again, or add --accept-older-backup. Nothing was removed."
    fi
  done
fi

# Containers outside the project, and what they use: none of it may be touched.
others=$(docker ps -a --format '{{.Names}}|{{.Label "com.docker.compose.project"}}' | awk -F'|' -v p="$PROJECT" '$2 != p { print $1 }' | sort)
used_by_others() {   # used_by_others <volume>: a container outside the project mounts it
  local user
  for user in $(docker ps -a --filter volume="$1" --format '{{.Names}}'); do
    grep -qx "$user" <<< "$others" && return 0
  done
  return 1
}
for volume in "${extra_volumes[@]}"; do
  docker volume inspect "$volume" >/dev/null 2>&1 || fail "--extra-volume $volume does not exist. Nothing was removed."
  used_by_others "$volume" && fail "--extra-volume $volume is used by another project's container. Nothing was removed."
  volumes+=("$volume")
done

# Anonymous volumes of the project's containers (removed with the containers by docker rm -v).
anonymous=$(for container in "${containers[@]}"; do
  docker inspect "$container" --format '{{range .Mounts}}{{if eq .Type "volume"}}{{.Name}}{{"\n"}}{{end}}{{end}}'
done | sed '/^$/d' | grep -v "^${PROJECT}_" | sort -u || true)

mapfile -t images < <({
  project_only_images
  docker images --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Repository}}:{{.Tag}}'
  docker images --format '{{.Repository}}:{{.Tag}}' | grep "^${PROJECT}/" || true
  if $tooling; then
    for image in "${TOOLING_IMAGES[@]}"; do
      docker image inspect "$image" >/dev/null 2>&1 || continue
      docker ps -a --filter ancestor="$image" --format '{{.Names}}' | grep -qxF -f <(printf '%s\n' "$others" | sed '/^$/d') \
        && continue   # another project's container runs it
      echo "$image"
    done
  fi
} | sed '/^$/d' | sort -u)

echo
echo "The cleanup removes ONLY these resources of the '$PROJECT' application:"
echo "  Containers (${#containers[@]}):"; printf '    %s\n' "${containers[@]}"
echo "  Anonymous volumes of those containers ($(sed '/^$/d' <<< "$anonymous" | wc -l)): no app data"
echo "  Networks (${#networks[@]}):"; printf '    %s\n' "${networks[@]}"
echo "  Volumes (${#volumes[@]}), all backed up except --extra-volume ones:"; printf '    %s\n' "${volumes[@]}"
echo "  Images (${#images[@]}):"; printf '    %s\n' "${images[@]}"
echo "  Container logs: removed with the containers (saved in logs/ of the backup)."
echo "  Build cache: not touched (BuildKit's cache is shared by every project)."
echo
echo "Kept (not this application): $(sed '/^$/d' <<< "$others" | wc -l) other containers:"
sed '/^$/d; s/^/    /' <<< "$others"
echo

if $dry_run; then
  echo "Dry run: nothing was removed."
  exit 0
fi

# ---- 3. confirmation -----------------------------------------------------------------------
if ! $yes; then
  if [[ ! -t 0 ]]; then
    fail "Not confirmed (no terminal to ask): run again with --yes to confirm. Nothing was removed."
  fi
  read -r -p "Type the project name ($PROJECT) to delete the resources above: " answer
  [[ "$answer" == "$PROJECT" ]] || { echo "Nothing was removed."; exit 1; }
fi

# ---- 4. removal ------------------------------------------------------------------------------
others_before="$others"
if (( ${#containers[@]} > 0 )); then
  log "Removing ${#containers[@]} containers (and their anonymous volumes) ..."
  docker rm -f -v "${containers[@]}" >/dev/null
fi
for network in "${networks[@]}"; do
  log "Removing network $network"; docker network rm "$network" >/dev/null
done
for volume in "${volumes[@]}"; do
  log "Removing volume $volume"; docker volume rm "$volume" >/dev/null
done
not_removed=()
for image in "${images[@]}"; do
  if docker image rm "$image" >/dev/null 2>&1; then
    log "Removed image $image"
  else
    not_removed+=("$image")
  fi
done

# ---- 5. check --------------------------------------------------------------------------------
left_containers=$(project_containers | sed '/^$/d' | wc -l)
left_volumes=$({ project_volumes; tooling_volumes; } | sed '/^$/d' | wc -l)
left_networks=$(project_networks | sed '/^$/d' | wc -l)
others_after=$(docker ps -a --format '{{.Names}}|{{.Label "com.docker.compose.project"}}' | awk -F'|' -v p="$PROJECT" '$2 != p { print $1 }' | sort)
echo
log "Left of '$PROJECT': $left_containers containers, $left_volumes volumes, $left_networks networks."
(( ${#not_removed[@]} == 0 )) || warn "Images not removed (still in use?): ${not_removed[*]}"
if [[ "$others_after" == "$others_before" ]]; then
  log "Other projects' containers are untouched ($(sed '/^$/d' <<< "$others_after" | wc -l))."
else
  warn "The list of other projects' containers changed during the cleanup; check docker ps -a."
fi
(( left_containers + left_volumes + left_networks == 0 )) || fail "Some resources of '$PROJECT' are left (see above)."
log "Cleanup complete. Restore later with RESTORE-GUIDE.md in the backup folder."
