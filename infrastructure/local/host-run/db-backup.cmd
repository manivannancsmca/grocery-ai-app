@echo off
rem ============================================================================================
rem Exports every service database now into infrastructure\local\backups (BACKUP_DIR in ..\.env):
rem
rem   grocery-<date>_<time>-manual.sql.gz      kept until you delete it
rem
rem The db-backup container also makes one export a day by itself (grocery-<date>_<time>-daily.sql.gz,
rem kept for BACKUP_KEEP_DAYS days). Import an export with db-restore.cmd. MySQL must be running.
rem ============================================================================================
setlocal EnableExtensions
for %%I in ("%~dp0..") do set "COMPOSE_DIR=%%~fI"

pushd "%COMPOSE_DIR%"
docker compose run --rm --no-deps --entrypoint /bin/bash db-backup /scripts/backup.sh manual
set "RESULT=%ERRORLEVEL%"
popd
if not "%RESULT%"=="0" echo The export failed. Is MySQL running? (docker compose up -d mysql)
exit /b %RESULT%
