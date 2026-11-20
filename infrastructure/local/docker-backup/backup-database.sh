#!/usr/bin/env bash
# =====================================================================================
# Database backup: every service database of the app (ai_db, catalog_db, ... vendor_db) into one SQL file:
#
#   <backup>/database/grocery-db-<timestamp>.sql
#
#   bash infrastructure/local/docker-backup/backup-database.sh <backup folder>
#
# - One consistent moment for all databases (--single-transaction over all of them in one run): an order, its
#   payment and its delivery always match.
# - Everything a database holds: tables with their rows, indexes and constraints (in CREATE TABLE), stored
#   procedures and functions (--routines), triggers (--triggers) and scheduled events (--events).
# - Each database is dropped and created again by the file (--add-drop-database), so a restore replaces it exactly.
# - The MySQL users and passwords are not in the file: infrastructure/local/mysql/init creates them (mysql-init).
# - MySQL must be running. The password never appears on a command line or in this script's output: mysqldump reads
#   it from MYSQL_PWD inside the container.
# =====================================================================================
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

backup_dir="${1:?Usage: backup-database.sh <backup folder>}"
stamp="${BACKUP_STAMP:-$(date +%Y%m%d-%H%M%S)}"
require_docker

mysql=$(service_container mysql)
[[ -n "$mysql" ]] || fail "There is no MySQL container in the '$PROJECT' project."
[[ "$(docker inspect -f '{{.State.Running}}' "$mysql")" == true ]] || fail "$mysql is not running (start it first)."

mkdir -p "$backup_dir/database"
out="$backup_dir/database/grocery-db-$stamp.sql"

databases=$(docker exec "$mysql" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B -e "
  SELECT schema_name FROM information_schema.schemata
  WHERE schema_name NOT IN (\"mysql\", \"sys\", \"information_schema\", \"performance_schema\")
  ORDER BY schema_name"')
[[ -n "$databases" ]] || fail "MySQL has no service database."
log "Dumping $(wc -w <<< "$databases") databases from $mysql: $(tr '\n' ' ' <<< "$databases")"

# --set-gtid-purged=OFF: the server runs with GTIDs; without it the file could not be imported back into it.
# --hex-blob: binary ids (UUIDs in BINARY(16)) are written as hex, safe in a text file.
# shellcheck disable=SC2016  # $MYSQL_ROOT_PASSWORD is expanded inside the container
docker exec "$mysql" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot \
    --single-transaction --quick --hex-blob --routines --events --triggers \
    --set-gtid-purged=OFF --no-tablespaces --add-drop-database --default-character-set=utf8mb4 \
    --databases '"$(tr '\n' ' ' <<< "$databases")" > "$out.partial"

# A complete dump ends with mysqldump's "-- Dump completed" line; only then does the file get its real name.
if ! tail -n 1 "$out.partial" | grep -q '^-- Dump completed'; then
  rm -f "$out.partial"
  fail "The dump is incomplete; nothing was kept."
fi
mv "$out.partial" "$out"
log "Database backup: $(basename "$out") ($(du -h "$out" | cut -f1))"
