#!/usr/bin/env bash
# =====================================================================================
# Database restore: imports the backup's SQL file (database/grocery-db-<timestamp>.sql) into the app's running MySQL.
#
#   bash restore-database.sh <backup folder> [--only order_db,payment_db] [--yes] [--force]
#
#   --only a_db,b_db   import only these databases; the others keep their current data
#   --yes              do not ask for confirmation
#   --force            import even though services are still connected to those databases
#   --compare          import nothing: only compare the rows of every table with the backup (checks a restore)
#
# Use it when the MySQL volume was NOT restored (a fresh MySQL, or putting only the database back). After restoring
# the volumes with restore-volumes.sh, the data is already there and this script is not needed.
#
# - Each chosen database is replaced completely (DROP DATABASE, CREATE, data).
# - MySQL's binary log is off for the import, so Debezium does not publish the backup's outbox rows a second time.
# - Afterwards the rows of every table are compared with the verification report of the backup.
# - Then start the services and rebuild search (RESTORE-GUIDE.md, steps 10-12).
# =====================================================================================
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

backup_dir="" only="" yes=false force=false compare=false
while (( $# )); do
  case "$1" in
    --only)    only="${2:?--only needs database names, e.g. --only order_db,payment_db}"; shift 2 ;;
    --yes)     yes=true; shift ;;
    --force)   force=true; shift ;;
    --compare) compare=true; shift ;;
    -*)      fail "Unknown option: $1" ;;
    *)       backup_dir="$1"; shift ;;
  esac
done
[[ -n "$backup_dir" && -d "$backup_dir" ]] || fail "Usage: restore-database.sh <backup folder> [--only a_db,b_db] [--yes] [--force]"
backup_dir="$(cd "$backup_dir" && pwd)"
stamp=$(basename "$backup_dir" | sed -n 's/^grocery-ai-app-backup-//p')
sql="$backup_dir/database/grocery-db-$stamp.sql"
[[ -s "$sql" ]] || fail "No SQL backup at $sql."
tail -n 1 "$sql" | grep -q '^-- Dump completed' || fail "The SQL backup is incomplete."
require_docker

mysql=$(service_container mysql)
[[ -n "$mysql" && "$(docker inspect -f '{{.State.Running}}' "$mysql" 2>/dev/null)" == true ]] \
  || fail "The app's MySQL is not running. Start it: cd infrastructure/local && docker compose up -d mysql mysql-init"
sql_query() {   # runs SQL as root inside the container; the password stays in the container's environment
  # shellcheck disable=SC2016
  docker exec -i "$mysql" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B --default-character-set=utf8mb4' <<< "$1"
}

mapfile -t in_file < <(sed -n 's/^-- Current Database: `\(.*\)`$/\1/p' "$sql" | awk '!seen[$0]++')
if [[ -n "$only" ]]; then
  IFS=',' read -r -a chosen <<< "${only// /}"
  for db in "${chosen[@]}"; do
    printf '%s\n' "${in_file[@]}" | grep -qx "$db" || fail "$db is not in the backup. It has: ${in_file[*]}"
  done
else
  chosen=("${in_file[@]}")
fi

import_databases() {
  # A running service keeps connections open with its own user (order_db -> order_user).
  local users connected answer keep
  users=$(printf "'%s_user'," "${chosen[@]%_db}")
  connected=$(sql_query "SELECT DISTINCT user FROM information_schema.processlist WHERE user IN (${users%,})" | tr '\n' ' ')
  if [[ -n "${connected// /}" ]] && ! $force; then
    fail "Services are still connected (database users: $connected). Stop them first, or add --force."
  fi

  echo "Import:   $(basename "$sql")"
  echo "Into:     $mysql"
  echo "Replaces: ${chosen[*]}"
  if ! $yes; then
    [[ -t 0 ]] || fail "Not confirmed (no terminal to ask): add --yes. Nothing was changed."
    read -r -p "The current data in these databases is replaced. Type yes to continue: " answer
    [[ "$answer" == yes ]] || { echo "Nothing was changed."; exit 1; }
  fi

  log "Importing ..."
  keep=",$(IFS=,; echo "${chosen[*]}"),"
  {
    echo "SET SESSION sql_log_bin = 0;"
    # The file's header (session settings) and the sections of the chosen databases.
    awk -v keep="$keep" '
      BEGIN { on = 1 }
      /^-- Current Database: `/ { db = $0; sub(/^-- Current Database: `/, "", db); sub(/`$/, "", db); on = index(keep, "," db ",") > 0 }
      on' "$sql"
  } | docker exec -i "$mysql" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4' \
    || fail "The import stopped with an error (above)."
  log "Imported ${#chosen[@]} databases."
}

$compare || import_databases

# Compare the rows of every table with the backup's verification report.
report="$backup_dir/verification-$stamp.txt"
if [[ ! -f "$report" ]]; then
  warn "No verification report in the backup: rows were not compared."
else
  expected=$(awk '/^Rows per table/ { on = 1; next } on && NF == 2 { print $1 "\t" $2 }' "$report" \
    | grep -E "^($(IFS='|'; echo "${chosen[*]}"))\." || true)
  mismatches=0
  while IFS=$'\t' read -r table rows; do
    [[ -z "$table" ]] && continue
    actual=$(sql_query "SELECT COUNT(*) FROM \`${table%%.*}\`.\`${table#*.}\`" 2>/dev/null || echo "missing")
    if [[ "$actual" != "$rows" ]]; then
      echo "  rows differ: $table backup $rows, now $actual"; mismatches=$((mismatches + 1))
    fi
  done <<< "$expected"
  if (( mismatches == 0 )); then
    log "Every table has the same number of rows as in the backup ($(wc -l <<< "$expected") tables)."
  else
    warn "$mismatches tables differ from the backup (see above)."
  fi
fi
$compare || log "Next: start the services, then rebuild search (RESTORE-GUIDE.md, steps 10-12)."
