#!/usr/bin/env bash
# =====================================================================================
# The db-backup container's main loop: one export a day (backup.sh daily).
#
# Every 10 minutes it checks: is it BACKUP_TIME or later (local time, TZ = BACKUP_TIMEZONE) and is there no daily
# export of today yet? Then it exports. So when the machine is off at BACKUP_TIME, the export is made as soon as
# Docker runs again that day. A failed export is tried again 10 minutes later.
# =====================================================================================
set -uo pipefail

BACKUP_TIME="${BACKUP_TIME:-00:00}"
if [[ ! "$BACKUP_TIME" =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]]; then
  echo "BACKUP_TIME must look like 00:00 or 21:30 (24-hour clock), not '$BACKUP_TIME'."
  exit 1
fi
echo "Daily database export at $BACKUP_TIME ($(date +%Z)), kept for ${BACKUP_KEEP_DAYS:-14} days, into the backups folder."

while true; do
  today=$(date +%F)
  # "HH:MM" strings compare correctly as text.
  if [[ ! "$(date +%H:%M)" < "$BACKUP_TIME" ]] && ! compgen -G "/backups/grocery-${today}_*-daily.sql.gz" > /dev/null; then
    /scripts/backup.sh daily || echo "The daily export failed; trying again in 10 minutes."
  fi
  sleep 600
done
