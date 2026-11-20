@echo off
rem ============================================================================================
rem Runs the platform with the Spring Boot services on this machine and everything else in Docker:
rem
rem   1. stops every Spring Boot container (gateway + 16 services), so nothing runs twice
rem   2. starts the infrastructure with Docker Compose: MySQL, Kafka, Schema Registry, Redis,
rem      Kafka Connect (Debezium), Elasticsearch, Mailpit, Kafka UI, the React dev server and
rem      db-backup (the daily database export into ..\backups; db-backup.cmd / db-restore.cmd)
rem   3. opens one Windows Terminal tab per Spring Boot service (run-service.cmd)
rem
rem Afterwards: http://localhost:6420 (app), http://localhost:8080 (gateway),
rem             http://localhost:8025 (emails), http://localhost:8070 (Kafka UI).
rem
rem Stop the services with Ctrl+C in their tabs, or all at once with stop-all.cmd.
rem Back to the all-Docker setup: stop-all.cmd, then the usual "docker compose --profile ... up -d".
rem
rem Optional: set GATEWAY_PORT=8090 before running when port 8080 is taken on this machine.
rem Monitoring (docker compose --profile observability up -d): put SERVICES_RUN_ON=host in ..\.env so Prometheus
rem scrapes the services on this machine (host.docker.internal) instead of the stopped containers.
rem ============================================================================================
setlocal EnableExtensions

if not defined GATEWAY_PORT set "GATEWAY_PORT=8080"
for %%I in ("%~dp0.") do set "HERE=%%~fI"
for %%I in ("%~dp0..") do set "COMPOSE_DIR=%%~fI"

rem Every Spring Boot service of docker-compose.yml (stopped in step 1).
set "JAVA_CONTAINERS=api-gateway identity-service customer-service vendor-service restaurant-service delivery-service catalog-command-service catalog-query-service inventory-service pricing-service cart-service order-service payment-service notification-service notification-service-2 ai-orchestrator-service review-service reporting-service"
rem What stays in Docker (step 2). The observability stack and Ollama are left out: start them
rem with "docker compose --profile observability up -d" / "--profile ai up -d" when you need them.
rem The AI assistants use Ollama when it is up before the ai-orchestrator-service tab starts;
rem without it they fall back to the "scripted" stand-in, which only understands fixed phrases.
set "INFRASTRUCTURE=mysql mysql-init db-backup kafka kafka-init schema-registry schema-init redis kafka-connect connect-init connect-watchdog elasticsearch mailpit kafka-ui frontend"
rem The services started on this machine (step 3), identity and the gateway first.
set "HOST_SERVICES=identity-service api-gateway customer-service vendor-service restaurant-service delivery-service catalog-command-service catalog-query-service inventory-service pricing-service cart-service order-service payment-service notification-service ai-orchestrator-service review-service reporting-service"

pushd "%COMPOSE_DIR%"

echo.
echo [1/3] Stopping the Spring Boot containers ...
docker compose --profile all stop %JAVA_CONTAINERS%
if errorlevel 1 goto :docker_failed

echo.
echo [2/3] Starting the infrastructure with Docker Compose ...
rem The React dev server proxies /api to the gateway on this machine instead of the gateway container.
set "VITE_PROXY_TARGET=http://host.docker.internal:%GATEWAY_PORT%"
rem A React dev server already running on this machine (npm run dev) holds the app's port: then the frontend
rem container is left out (it could not publish the same port) and that dev server stays the app.
if not defined FRONTEND_HOST_PORT set "FRONTEND_HOST_PORT=6420"
netstat -ano -p tcp | findstr /r /c:":%FRONTEND_HOST_PORT% .*LISTENING" >nul
if not errorlevel 1 (
  echo   Port %FRONTEND_HOST_PORT% is already in use, probably by a React dev server on this machine: the frontend container is not started.
  set "INFRASTRUCTURE=%INFRASTRUCTURE:frontend=%"
)
rem Right after Docker Desktop starts, a slow container (Kafka Connect) can miss its health check once:
rem try up to three times before giving up.
set "ATTEMPT=0"
:compose_up
set /a ATTEMPT+=1
docker compose --profile all up -d %INFRASTRUCTURE%
if not errorlevel 1 goto :compose_ready
if %ATTEMPT% geq 3 goto :docker_failed
echo   Not everything is healthy yet - trying again in 30 seconds (attempt %ATTEMPT% of 3) ...
ping -n 31 127.0.0.1 >nul
goto :compose_up
:compose_ready
rem The services publish Avro events: wait until the schemas are registered.
for /f %%C in ('docker compose --profile all ps -aq schema-init') do docker wait %%C >nul
popd

echo.
echo [3/3] Opening one terminal tab per service ...
where wt >nul 2>&1
if errorlevel 1 goto :no_windows_terminal
for %%S in (%HOST_SERVICES%) do (
  echo   %%S
  wt -w grocery-services new-tab --title %%S --suppressApplicationTitle -d "%HERE%" cmd /k .\run-service.cmd %%S
  rem A short pause between starts keeps the machine responsive (17 JVMs).
  ping -n 4 127.0.0.1 >nul
)
goto :done

:no_windows_terminal
echo   Windows Terminal (wt.exe) was not found: opening one window per service instead.
for %%S in (%HOST_SERVICES%) do (
  echo   %%S
  start "%%S" /d "%HERE%" cmd /k .\run-service.cmd %%S
  ping -n 4 127.0.0.1 >nul
)

:done
echo.
echo All services are starting (give them a minute or two).
echo   App      http://localhost:6420
echo   Gateway  http://localhost:%GATEWAY_PORT%
echo   Emails   http://localhost:8025
echo   Kafka UI http://localhost:8070
echo   Backups  %COMPOSE_DIR%\backups (one database export a day)
exit /b 0

:docker_failed
popd
echo.
echo Docker Compose failed - is Docker Desktop running? Nothing was started on this machine.
exit /b 1
