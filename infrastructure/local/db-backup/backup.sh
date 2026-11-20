#!/usr/bin/env bash
# =====================================================================================
# Exports every service database (order_db, catalog_db, identity_db, ...) into ONE file in the backups folder:
#
#   grocery-<date>_<time>-<kind>.sql.gz      e.g. grocery-2026-11-17_000512-daily.sql.gz
#
#   kind = daily           made by schedule.sh; deleted after BACKUP_KEEP_DAYS days
#          manual          made by host-run\db-backup.cmd; kept until you delete it
#          before-restore  made by restore.sh just before an import; kept until you delete it
#
# Runs inside the db-backup container (mysql image: the same mysqldump version as the server).
# All databases come from one consistent moment (--single-transaction over all of them in one run), so an order,
# its payment and its delivery always match after an import.
# =====================================================================================
set -euo pipefail

KIND="${1:-manual}"
DIR=/backups
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"

# The password goes in a temporary option file, not on the command line.
cnf=$(mktemp)
partial=""
trap 'rm -f "$cnf" ${partial:+"$partial"}' EXIT
printf '[client]\nhost=%s\nuser=root\npassword=%s\n' "${MYSQL_HOST:-mysql}" "$MYSQL_ROOT_PASSWORD" > "$cnf"

# Every database except MySQL's own: a service added later is exported without changing this script.
databases=$(mysql --defaults-extra-file="$cnf" -N -B -e "
  SELECT schema_name FROM information_schema.schemata
  WHERE schema_name NOT IN ('mysql', 'sys', 'information_schema', 'performance_schema')
  ORDER BY schema_name")
if [[ -z "$databases" ]]; then
  echo "No service database found: nothing to export."
  exit 1
fi

mkdir -p "$DIR"
file="$DIR/grocery-$(date +%F_%H%M%S)-$KIND.sql.gz"
partial="$file.partial"

# --add-drop-database: an import replaces each database completely (no table left over from a later migration).
# --set-gtid-purged=OFF: the server runs with GTIDs; without it the file could not be imported back into it.
# --hex-blob: binary ids (UUIDs stored as BINARY(16)) are written as hex, safe in a text file.
# shellcheck disable=SC2086  # $databases is a list of names
mysqldump --defaults-extra-file="$cnf" \
  --single-transaction --quick --hex-blob \
  --routines --events --triggers \
  --set-gtid-purged=OFF --no-tablespaces \
  --add-drop-database --default-character-set=utf8mb4 \
  --databases $databases | gzip -6 > "$partial"

# A complete export ends with mysqldump's "-- Dump completed" line. Only then does it get its real name.
if ! gunzip -c "$partial" | tail -n 1 | grep -q '^-- Dump completed'; then
  echo "The export is incomplete; it was not kept."
  exit 1
fi
mv "$partial" "$file"
partial=""
echo "Exported $(wc -w <<< "$databases") databases to $(basename "$file") ($(du -h "$file" | cut -f1))"

# Only the daily exports expire; manual and before-restore exports stay until you delete them.
find "$DIR" -maxdepth 1 -name 'grocery-*-daily.sql.gz' -mtime +"$KEEP_DAYS" -print -delete \
  | sed 's|.*/|Deleted the old export |'
