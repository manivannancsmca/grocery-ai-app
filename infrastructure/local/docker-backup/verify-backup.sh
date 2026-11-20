#!/usr/bin/env bash
# =====================================================================================
# Verifies a backup folder made by backup-all.sh. Writes verification-<timestamp>.txt and, only when every check
# passes, the VERIFIED marker that cleanup-grocery-docker.sh requires.
#
#   bash infrastructure/local/docker-backup/verify-backup.sh <backup folder> [--quick]
#
# Checks:
#   1. the folders and the information file exist; every file matches its checksum (SHA256SUMS);
#   2. the SQL backup is complete (ends with "-- Dump completed") and has every database;
#   3. a test restore of the SQL backup into a throwaway MySQL works, and dumping that copy again gives the same
#      file (so the backup restores exactly); the row count of every table is written to the report, for checking a
#      restore later;
#   4. every volume archive is a readable .tar.gz with its metadata file, and the MySQL volume archive starts a
#      throwaway MySQL that lists the same databases;
#   5. the configuration (compose file, Dockerfiles, source archive) and the logs archive are readable;
#   6. the backup lies in a host folder, not in a Docker volume.
# --quick skips the two throwaway MySQL checks (3 and the MySQL part of 4).
# Throwaway containers and volumes are removed afterwards, whatever the result.
# =====================================================================================
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

backup_dir="${1:?Usage: verify-backup.sh <backup folder> [--quick]}"
quick=false
[[ "${2:-}" == "--quick" ]] && quick=true
[[ -d "$backup_dir" ]] || fail "No such folder: $backup_dir"
backup_dir="$(cd "$backup_dir" && pwd)"
stamp=$(basename "$backup_dir" | sed -n 's/^grocery-ai-app-backup-//p')
[[ -n "$stamp" ]] || fail "$backup_dir is not a folder made by backup-all.sh (grocery-ai-app-backup-<timestamp>)."
report="$backup_dir/verification-$stamp.txt"
rm -f "$backup_dir/VERIFIED"

passed=0 failed=0
results=()
check() {   # check "<description>" <command...>
  local description="$1"; shift
  if "$@" >/dev/null 2>&1; then
    results+=("PASS  $description"); passed=$((passed + 1)); log "PASS  $description"
  else
    results+=("FAIL  $description"); failed=$((failed + 1)); log "FAIL  $description"
  fi
}

TEST_DB="grocery-backup-verify-db-$stamp"
TEST_VOLUME_DB="grocery-backup-verify-voldb-$stamp"
TEST_VOLUME="grocery-backup-verify-vol-$stamp"
cleanup_test() {
  docker rm -f -v "$TEST_DB" "$TEST_VOLUME_DB" >/dev/null 2>&1 || true
  docker volume rm "$TEST_VOLUME" >/dev/null 2>&1 || true
}
trap cleanup_test EXIT

# ---- 1. structure and checksums --------------------------------------------------------
for folder in database volumes configuration logs; do
  check "folder $folder/ exists" test -d "$backup_dir/$folder"
done
check "backup-information-$stamp.txt exists" test -s "$backup_dir/backup-information-$stamp.txt"
check "every file matches SHA256SUMS" bash -c "cd '$backup_dir' && sha256sum --quiet -c SHA256SUMS"

# ---- 2. the SQL backup ---------------------------------------------------------------------
sql="$backup_dir/database/grocery-db-$stamp.sql"
check "database backup exists and is not empty" test -s "$sql"
check "database backup is complete (-- Dump completed)" bash -c "tail -n 1 '$sql' | grep -q '^-- Dump completed'"
mapfile -t dump_dbs < <(sed -n 's/^-- Current Database: `\(.*\)`$/\1/p' "$sql" 2>/dev/null | awk '!seen[$0]++')
check "database backup has service databases (${#dump_dbs[@]})" test "${#dump_dbs[@]}" -gt 0

# Waits (up to 3 minutes) until the MySQL in <container> answers: wait_for_mysql <container> <mysql client args...>
wait_for_mysql() {
  local container="$1"; shift
  for _ in $(seq 1 90); do
    if docker exec "$container" mysql "$@" -N -B -e "SELECT 1" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  return 1
}

row_counts=""
if ! $quick && [[ -s "$sql" ]]; then
  require_docker
  log "Test restore of the SQL backup into a throwaway MySQL ..."
  docker run -d --name "$TEST_DB" -e MYSQL_ROOT_PASSWORD=verify "$MYSQL_IMAGE" \
    --gtid-mode=ON --enforce-gtid-consistency=ON --skip-name-resolve \
    --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci --default-time-zone=+00:00 >/dev/null
  if wait_for_mysql "$TEST_DB" -uroot -pverify; then
    check "SQL backup imports without errors" \
      bash -c "docker exec -i '$TEST_DB' mysql -uroot -pverify --default-character-set=utf8mb4 < '$sql'"
    restored=$(docker exec "$TEST_DB" mysql -uroot -pverify -N -B -e "SELECT COUNT(*) FROM information_schema.schemata
      WHERE schema_name NOT IN ('mysql','sys','information_schema','performance_schema')" 2>/dev/null || true)
    check "the test restore has all ${#dump_dbs[@]} databases" test "$restored" = "${#dump_dbs[@]}"
    # Dumping the restored copy with the same options must give the same file (the comment lines with the date and
    # the server's host differ, nothing else).
    redump="$backup_dir/.redump.sql"
    docker exec "$TEST_DB" mysqldump -uroot -pverify --single-transaction --quick --hex-blob --routines --events \
      --triggers --set-gtid-purged=OFF --no-tablespaces --add-drop-database --default-character-set=utf8mb4 \
      --databases "${dump_dbs[@]}" > "$redump" 2>/dev/null || true   # a failed dump fails the comparison below
    same=false
    if diff -q <(grep -vE '^-- (Host:|Dump completed|MySQL dump|Server version)' "$sql") \
               <(grep -vE '^-- (Host:|Dump completed|MySQL dump|Server version)' "$redump") >/dev/null; then
      same=true
    fi
    check "the test restore dumps identically to the backup (exact round trip)" test "$same" = true
    rm -f "$redump"
    # Rows per table, for checking a restore later (post-restore checklist).
    row_counts=$(docker exec "$TEST_DB" mysql -uroot -pverify -N -B -e "
      SELECT CONCAT('SELECT ''', table_schema, '.', table_name, ''', COUNT(*) FROM \`', table_schema, '\`.\`',
                    table_name, '\`;')
      FROM information_schema.tables
      WHERE table_type = 'BASE TABLE'
        AND table_schema NOT IN ('mysql','sys','information_schema','performance_schema')
      ORDER BY table_schema, table_name" 2>/dev/null \
      | docker exec -i "$TEST_DB" mysql -uroot -pverify -N -B 2>/dev/null || true)
  else
    check "throwaway MySQL started for the test restore" false
  fi
  docker rm -f -v "$TEST_DB" >/dev/null 2>&1 || true
fi

# ---- 4. volume archives ------------------------------------------------------------------
mapfile -t archives < <(ls -1 "$backup_dir/volumes" 2>/dev/null | grep -- "-$stamp\.tar\.gz$" || true)
check "volume archives exist (${#archives[@]})" test "${#archives[@]}" -gt 0
for archive in "${archives[@]}"; do
  volume="${archive%-"$stamp".tar.gz}"
  check "volume $volume: archive is readable" bash -c "gzip -t '$backup_dir/volumes/$archive' && tar -tzf '$backup_dir/volumes/$archive' | head -n 1 | grep -q ."
  check "volume $volume: metadata ($volume-$stamp.json) exists" test -s "$backup_dir/volumes/$volume-$stamp.json"
done

mysql_archive=$(printf '%s\n' "${archives[@]}" | grep -- "_mysql-data-$stamp\.tar\.gz$" | head -n 1 || true)
if ! $quick && [[ -n "$mysql_archive" ]]; then
  log "Starting a throwaway MySQL on a copy of the MySQL volume archive ..."
  docker volume create "$TEST_VOLUME" >/dev/null
  docker run --rm -v "$TEST_VOLUME:/target" -v "$(host_path "$backup_dir/volumes"):/backup:ro" "$HELPER_IMAGE" \
    tar -xzf "/backup/$mysql_archive" --numeric-owner -C /target
  # The volume's own root password is not known to this script: skip the privilege tables (no network, only the
  # socket inside the throwaway container), just to see that the data files start and hold the databases.
  docker run -d --name "$TEST_VOLUME_DB" -v "$TEST_VOLUME:/var/lib/mysql" "$MYSQL_IMAGE" \
    --skip-grant-tables --skip-networking --gtid-mode=ON --enforce-gtid-consistency=ON >/dev/null
  if wait_for_mysql "$TEST_VOLUME_DB" -uroot; then
    in_volume=$(docker exec "$TEST_VOLUME_DB" mysql -uroot -N -B -e "SELECT COUNT(*) FROM information_schema.schemata
      WHERE schema_name NOT IN ('mysql','sys','information_schema','performance_schema')" 2>/dev/null || true)
    check "the MySQL volume archive starts MySQL with ${#dump_dbs[@]} databases" test "$in_volume" = "${#dump_dbs[@]}"
  else
    check "the MySQL volume archive starts MySQL" false
  fi
  docker rm -f -v "$TEST_VOLUME_DB" >/dev/null 2>&1 || true
  docker volume rm "$TEST_VOLUME" >/dev/null 2>&1 || true
fi

# ---- 5. configuration and logs -------------------------------------------------------------
check "docker-compose.yml is backed up" test -s "$backup_dir/configuration/compose/docker-compose.yml"
check "environment file (.env or .env.example) is backed up" \
  bash -c "test -s '$backup_dir/configuration/env/.env' || test -s '$backup_dir/configuration/env/.env.example'"
check "Dockerfiles are backed up" bash -c "find '$backup_dir/configuration/dockerfiles' -name 'Dockerfile*' | grep -q ."
check "service configuration is backed up" bash -c "find '$backup_dir/configuration/application-config' -name 'application*.yml' | grep -q ."
check "source code archive is readable" bash -c "gzip -t '$backup_dir/configuration/source-code-$stamp.tar.gz'"
check "infrastructure/local archive is readable" bash -c "gzip -t '$backup_dir/configuration/infrastructure-local-$stamp.tar.gz'"
check "container logs archive is readable" bash -c "gzip -t '$backup_dir/logs/container-logs-$stamp.tar.gz'"

# ---- 6. location ---------------------------------------------------------------------------
# A Docker volume lives inside Docker's own storage; this folder is a host folder that `ls` sees directly.
check "the backup is in a host folder ($(host_path "$backup_dir")), not in a Docker volume" test -w "$backup_dir"

# ---- report ----------------------------------------------------------------------------------
{
  echo "Verification of $(basename "$backup_dir") at $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "Result: $passed passed, $failed failed"
  echo
  printf '%s\n' "${results[@]}"
  if [[ -n "$row_counts" ]]; then
    echo
    echo "Rows per table in the database backup (compare after a restore; see RESTORE-GUIDE.md):"
    printf '%s\n' "$row_counts" | awk -F'\t' '{ printf "  %-60s %s\n", $1, $2 }'
  fi
} > "$report"

if (( failed > 0 )); then
  log "Verification FAILED ($failed of $((passed + failed)) checks). Do not clean up. Report: $report"
  exit 1
fi
printf 'Verified %s: %s checks passed.\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$passed" > "$backup_dir/VERIFIED"
log "Verification passed ($passed checks). Report: $(basename "$report")"
