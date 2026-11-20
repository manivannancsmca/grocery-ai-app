@echo off
rem ============================================================================================
rem Imports a database export (the daily export, or one made with db-backup.cmd) back into MySQL.
rem
rem   db-restore.cmd                                        list the exports
rem   db-restore.cmd latest                                 the newest daily or manual export
rem   db-restore.cmd grocery-2026-11-17_000512-daily.sql.gz an export by name
rem   db-restore.cmd latest --only order_db,payment_db      only these databases (the others keep their data)
rem   options: --yes (do not ask), --force (import although services are still connected)
rem
rem Before: stop the Spring Boot services (Ctrl+C in their tabs, or stop-all.cmd). The current data is saved
rem         first (grocery-<now>-before-restore.sql.gz), so an import can be undone by importing that file.
rem After:  start the services (start-all.cmd), then rebuild search:
rem         node infrastructure\local\scripts\rebuild-search.mjs
rem An export from another machine: copy the .sql.gz file into infrastructure\local\backups first.
rem ============================================================================================
setlocal EnableExtensions
for %%I in ("%~dp0..") do set "COMPOSE_DIR=%%~fI"

pushd "%COMPOSE_DIR%"
docker compose run --rm --no-deps --entrypoint /bin/bash db-backup /scripts/restore.sh %*
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%
