#!/usr/bin/env bash
# =====================================================================================
# Shared settings and helpers of the Docker backup, cleanup and restore scripts in this folder.
# Sourced by the other scripts; not run on its own. Run the scripts from Git Bash (Windows) or bash (Linux/macOS).
#
# Which Docker resources belong to the app is never guessed from names alone:
#   - containers, named volumes and networks carry the Docker Compose label com.docker.compose.project=grocery;
#   - the app's build-cache volumes, made outside Compose, are named grocery-* and have no Compose label;
#   - images are the ones the app's containers run, minus any image another project's container also uses.
# =====================================================================================
set -euo pipefail

PROJECT="${COMPOSE_PROJECT:-grocery}"            # the Docker Compose project name of the app
HELPER_IMAGE="${HELPER_IMAGE:-alpine:3}"          # a small image that reads and writes volume contents
MYSQL_IMAGE="${MYSQL_IMAGE:-mysql:8.4.13}"        # the app's MySQL version, for test restores

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
COMPOSE_DIR="$REPO_ROOT/infrastructure/local"
# Backups live next to the repository, outside every Docker volume and outside the repository itself.
DEFAULT_BACKUP_ROOT="$(cd "$REPO_ROOT/.." && pwd)/grocery-ai-app-backups"

# Git Bash would rewrite paths such as /backup into Windows paths before Docker sees them.
export MSYS_NO_PATHCONV=1

log()  { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }
warn() { printf '%s  WARNING: %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
fail() { printf '%s  ERROR: %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

# A host folder as `docker run -v` expects it: D:/... in Git Bash on Windows, unchanged elsewhere.
host_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

require_docker() {
  docker info >/dev/null 2>&1 || fail "Docker is not running. Start Docker Desktop and try again."
}

# Every container of the Compose project (running or stopped), one name per line.
project_containers() {
  docker ps -a --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Names}}' | sort
}

# The running ones only.
running_project_containers() {
  docker ps --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Names}}' | sort
}

# The container of one Compose service (e.g. mysql), running or not.
service_container() {
  docker ps -a --filter "label=com.docker.compose.project=$PROJECT" \
    --filter "label=com.docker.compose.service=$1" --format '{{.Names}}' | head -n 1
}

# Named volumes Compose created for the project (grocery_mysql-data, ...).
project_volumes() {
  docker volume ls --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Name}}' | sort
}

# The app's own volumes made outside Compose (build caches such as grocery-m2): named grocery-*, no Compose label.
tooling_volumes() {
  docker volume ls --format '{{.Name}}|{{.Label "com.docker.compose.project"}}' \
    | awk -F'|' -v prefix="$PROJECT-" 'index($1, prefix) == 1 && $2 == "" { print $1 }' | sort
}

# Networks Compose created for the project (grocery_default).
project_networks() {
  docker network ls --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Name}}' | sort
}

# Images the project's containers run, minus every image a container of another project (or none) also runs.
project_only_images() {
  local ours theirs
  ours=$(docker ps -a --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Image}}' | sort -u)
  theirs=$(docker ps -a --format '{{.Image}}|{{.Label "com.docker.compose.project"}}' \
    | awk -F'|' -v p="$PROJECT" '$2 != p { print $1 }' | sort -u)
  comm -23 <(printf '%s\n' "$ours" | sed '/^$/d') <(printf '%s\n' "$theirs" | sed '/^$/d')
}

# sha256 of a file (the first word of sha256sum).
checksum() { sha256sum "$1" | cut -d' ' -f1; }
