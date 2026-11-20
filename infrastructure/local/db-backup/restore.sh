#!/usr/bin/env bash
# =====================================================================================
# Imports an export made by backup.sh (grocery-*.sql.gz in the backups folder) back into MySQL.
# On Windows: host-run\db-restore.cmd <file|latest> [options]. It runs this script in the db-backup container.
#
#   (no arguments)         list the exports
#   <file>                 an export's file name, e.g. grocery-2026-11-17_000512-daily.sql.gz
#   latest                 the newest daily or manual export
#   --only a_db,b_db       import only these databases; the others keep their current data
#   --yes                  do not ask for confirmation
#   --force                import even though services are still connected to those databases
#
# What it does:
#   1. Checks the file (a complete gzip, an export made by backup.sh) and that no service is connected to the
#      databases it replaces. Stop the services first.
#   2. Saves the current data (grocery-<now>-before-restore.sql.gz), so the import itself can be undone.
#   3. Replaces each database with the export's copy (DROP DATABASE, CREATE, the data). Binary logging is off for
#      the import, so Debezium does not publish the outbox rows of the export a second time.
# Afterwards: start the services, then rebuild search (node infrastructure/local/scripts/rebuild-search.mjs).
# =====================================================================================
set -euo pipefail

DIR=/backups

usage() {
  echo "Usage: db-restore <file|latest> [--only order_db,payment_db] [--yes] [--force]"
}

list() {
  echo "Exports in the backups folder (newest first):"
  local found=false
  while IFS= read -r name; do
    found=true
    printf '  %-48s %6s\n' "$name" "$(du -h "$DIR/$name" | cut -f1)"
  done < <(ls -1 "$DIR" 2>/dev/null | grep -E '^grocery-.*\.sql\.gz$' | sort -r)
  $found || echo "  (none yet)"
}

# ---- arguments ----------------------------------------------------------------------
target="" only="" only_given=false yes=false force=false
while (( $# )); do
  case "$1" in
    --only)    only_given=true; only="${2:-}"; shift; (( $# )) && shift ;;
    --only=*)  only_given=true; only="${1#--only=}"; shift ;;
    --yes|-y)  yes=true; shift ;;
    --force)   force=true; shift ;;
    -h|--help) usage; exit 0 ;;
    -*)        echo "Unknown option: $1"; usage; exit 2 ;;
    *)         target="$1"; shift ;;
  esac
done

if $only_given && [[ -z "${only// /}" || "$only" == -* ]]; then
  echo "--only needs the database names, e.g. --only order_db,payment_db"
  exit 2
fi
if [[ -z "$target" ]]; then
  list
  echo
  usage
  exit 0
fi

# ---- which file ---------------------------------------------------------------------
if [[ "$target" == latest ]]; then
  name=$(ls -1 "$DIR" 2>/dev/null | grep -E '^grocery-.*-(daily|manual)\.sql\.gz$' | sort | tail -n 1 || true)
  if [[ -z "$name" ]]; then
    echo "There is no daily or manual export yet."
    exit 1
  fi
else
  name=$(basename "$target")
fi
file="$DIR/$name"
if [[ ! -f "$file" ]]; then
  echo "There is no export named '$name' in the backups folder. (A file from elsewhere: copy it into that folder first.)"
  echo
  list
  exit 1
fi

if ! gzip -t "$file" 2>/dev/null; then
  echo "$name is damaged: it is not a complete .gz file."
  exit 1
fi
# The databases in the file: mysqldump starts each one with "-- Current Database: `name`" (twice: a second pass
# writes the views), so each name is kept once.
mapfile -t in_file < <(gunzip -c "$file" | sed -n 's/^-- Current Database: `\(.*\)`$/\1/p' | awk '!seen[$0]++')
if (( ${#in_file[@]} == 0 )); then
  echo "$name is not an export made by backup.sh (no database in it)."
  exit 1
fi

# ---- which databases ----------------------------------------------------------------
if [[ -n "$only" ]]; then
  IFS=',' read -r -a selected <<< "${only// /}"
  for db in "${selected[@]}"; do
    if ! printf '%s\n' "${in_file[@]}" | grep -qx "$db"; then
      echo "$db is not in $name. It has: ${in_file[*]}"
      exit 1
    fi
  done
else
  selected=("${in_file[@]}")
fi

cnf=$(mktemp)
trap 'rm -f "$cnf"' EXIT
printf '[client]\nhost=%s\nuser=root\npassword=%s\n' "${MYSQL_HOST:-mysql}" "$MYSQL_ROOT_PASSWORD" > "$cnf"

# ---- are services still using those databases? --------------------------------------
# Each service signs in with its own user, named after its database (order_db -> order_user). A running service
# keeps connections open, so a connected user means the service is still running.
users=$(printf "'%s_user'," "${selected[@]%_db}")
connected=$(mysql --defaults-extra-file="$cnf" -N -B -e \
  "SELECT DISTINCT user FROM information_schema.processlist WHERE user IN (${users%,}) ORDER BY user" | tr '\n' ' ')
if [[ -n "${connected// /}" ]]; then
  if $force; then
    echo "Warning: still connected ($connected); importing anyway (--force)."
  else
    echo "Services are still connected to these databases (database users: $connected)."
    echo "Stop them first (Ctrl+C in their windows, or host-run\\stop-all.cmd), or add --force."
    exit 1
  fi
fi

# ---- confirm -----------------------------------------------------------------------
echo "Import:   $name"
echo "Replaces: ${selected[*]}"
if ! $yes; then
  answer=""
  read -r -p "The current data in these databases is replaced (a copy is saved first). Type yes to continue: " answer || true
  if [[ "$answer" != yes ]]; then
    echo "Nothing was changed."
    exit 1
  fi
fi

# ---- 1. save the current data --------------------------------------------------------
# (A new, empty MySQL has no service database yet: then there is nothing to save.)
existing=$(mysql --defaults-extra-file="$cnf" -N -B -e "SELECT COUNT(*) FROM information_schema.schemata
  WHERE schema_name NOT IN ('mysql', 'sys', 'information_schema', 'performance_schema')")
if (( existing > 0 )); then
  echo "Saving the current data first ..."
  /scripts/backup.sh before-restore
  saved=$(ls -1 "$DIR" | grep -E '^grocery-.*-before-restore\.sql\.gz$' | sort | tail -n 1 || true)
  undo="The data from before is saved in $saved (import it to undo)."
else
  echo "MySQL has no service database yet: nothing to save first."
  undo="There was no data before."
fi

# ---- 2. import -----------------------------------------------------------------------
echo "Importing ..."
keep=",$(IFS=,; echo "${selected[*]}"),"
# The awk filter passes the file's header (session settings) and the sections of the chosen databases.
if ! {
  echo "SET SESSION sql_log_bin = 0;"
  gunzip -c "$file" | awk -v keep="$keep" '
    BEGIN { on = 1 }
    /^-- Current Database: `/ {
      db = $0
      sub(/^-- Current Database: `/, "", db)
      sub(/`$/, "", db)
      on = index(keep, "," db ",") > 0
    }
    on'
} | mysql --defaults-extra-file="$cnf" --default-character-set=utf8mb4; then
  echo
  echo "The import stopped with an error (above). $undo"
  exit 1
fi

echo
echo "Imported ${#selected[@]} database(s) from $name. $undo"
echo "Next:"
echo "  1. Start the services (host-run\\start-all.cmd, or run-service.cmd <name> in each window)."
echo "  2. Rebuild search from the imported data:  node infrastructure/local/scripts/rebuild-search.mjs"
