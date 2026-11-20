@echo off
rem ============================================================================================
rem Rebuilds the service jars that run-service.cmd starts (services\<name>\target\<name>-*.jar).
rem The build runs inside the Maven + JDK 25 container the project already uses for its tests,
rem so no JDK 25 or Maven setup is needed on this machine. Tests are skipped.
rem
rem   build-jars.cmd                          every service
rem   build-jars.cmd identity-service         only the named services (space separated)
rem
rem Restart a service (Ctrl+C in its tab, then run-service.cmd <name>) to pick up its new jar.
rem Each service is built with "clean package": without clean, a change in grocery-common alone (same version
rem number) left the old library inside the service's jar (the jar counted as up to date, already repackaged).
rem ============================================================================================
setlocal EnableExtensions
for %%I in ("%~dp0..\..\..") do set "ROOT=%%~fI"

set "SERVICES=%*"
if not defined SERVICES set "SERVICES=api-gateway identity-service customer-service vendor-service restaurant-service delivery-service catalog-command-service catalog-query-service inventory-service pricing-service cart-service order-service payment-service notification-service ai-orchestrator-service review-service reporting-service"

docker run --rm -v "%ROOT%:/workspace" -v grocery-m2:/root/.m2 -w /workspace ^
  maven:3.9.16-eclipse-temurin-25-noble bash -c ^
  "set -e; mvn -q -B -f libs/grocery-event-schemas/pom.xml install -DskipTests; mvn -q -B -f libs/grocery-common/pom.xml install -DskipTests; for s in %SERVICES%; do echo \"== $s\"; mvn -q -B -f services/$s/pom.xml clean package -DskipTests; done; echo 'All jars built.'"
exit /b %ERRORLEVEL%
