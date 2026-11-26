@echo off
rem ============================================================================================
rem Runs ONE Spring Boot service on this machine (not in Docker), against the infrastructure
rem that Docker Compose provides (MySQL 3307, Kafka 9092, Redis 6379, Schema Registry 8081, ...).
rem
rem   run-service.cmd <service-name>          e.g.  run-service.cmd identity-service
rem
rem start-all.cmd opens one terminal tab per service with this script. Ctrl+C stops the service.
rem
rem Needs a Java 25 runtime. It is looked for in this order:
rem   1. %JAVA25_HOME%
rem   2. a JDK 25 under "Program Files" (Java, Eclipse Adoptium, Microsoft) or %USERPROFILE%\.jdks
rem   3. the Java 25 runtime bundled with the VS Code "Language Support for Java" extension
rem
rem The jar comes from services\<name>\target (build-jars.cmd rebuilds them after a code change).
rem Any variable set below can be overridden by setting it before this script runs.
rem ============================================================================================
setlocal EnableExtensions

set "SERVICE=%~1"
if "%SERVICE%"=="" (
  echo Usage: %~nx0 ^<service-name^>
  exit /b 2
)
for %%I in ("%~dp0..\..\..") do set "ROOT=%%~fI"
set "SERVICE_DIR=%ROOT%\services\%SERVICE%"
if not exist "%SERVICE_DIR%\pom.xml" (
  echo Unknown service "%SERVICE%": "%SERVICE_DIR%" has no pom.xml
  exit /b 2
)
title %SERVICE%

call :find_java
if not defined JAVA_EXE (
  echo No Java 25 runtime found. Install a JDK 25 or set JAVA25_HOME to one.
  exit /b 1
)

set "JAR="
for %%F in ("%SERVICE_DIR%\target\%SERVICE%-*.jar") do if /i "%%~xF"==".jar" set "JAR=%%~fF"
if not defined JAR (
  echo No jar in "%SERVICE_DIR%\target". Run build-jars.cmd first.
  exit /b 1
)

rem ---- settings shared by every service -------------------------------------------------------
rem "local" profile: readable console logs, localhost infrastructure (see each application-local.yml).
if not defined SPRING_PROFILES_ACTIVE set "SPRING_PROFILES_ACTIVE=local"
rem No trace collector runs by default: sample nothing, so the consoles stay free of export errors.
if not defined TRACING_SAMPLING_PROBABILITY set "TRACING_SAMPLING_PROBABILITY=0"
rem Same heap as the containers get (75 %% of their 384 MB limit).
set "HEAP=288m"

rem ---- per-service settings (the values Docker Compose passes to the containers) ---------------
if /i "%SERVICE%"=="api-gateway" if defined GATEWAY_PORT set "SERVER_PORT=%GATEWAY_PORT%"
rem The browser's origin the gateway accepts: the React app on http://localhost:6420.
if /i "%SERVICE%"=="api-gateway" if not defined GATEWAY_ALLOWED_ORIGINS set "GATEWAY_ALLOWED_ORIGINS=http://localhost:6420"
if /i "%SERVICE%"=="identity-service" (
  if not defined BOOTSTRAP_ADMIN_EMAIL set "BOOTSTRAP_ADMIN_EMAIL=admin@grocery.local"
  if not defined BOOTSTRAP_ADMIN_PASSWORD set "BOOTSTRAP_ADMIN_PASSWORD=Admin@12345"
  if not defined DEV_SEED_ENABLED set "DEV_SEED_ENABLED=true"
)
if /i "%SERVICE%"=="notification-service" (
  if not defined GROCERY_MAIL_ENABLED set "GROCERY_MAIL_ENABLED=true"
  if not defined SPRING_MAIL_HOST set "SPRING_MAIL_HOST=localhost"
  if not defined SPRING_MAIL_PORT set "SPRING_MAIL_PORT=1025"
  if not defined APP_BASE_URL set "APP_BASE_URL=http://localhost:6420"
)
if /i "%SERVICE%"=="payment-service" call :load_payment_settings
if /i "%SERVICE%"=="ai-orchestrator-service" (
  set "HEAP=384m"
  rem The real model (Ollama) when it answers with the model pulled ("docker compose --profile ai up -d");
  rem otherwise "scripted", a stand-in without a model that only understands fixed phrases.
  rem Set AI_PROVIDER=ollama or AI_PROVIDER=scripted (and OLLAMA_BASE_URL) to choose yourself, or AI_PROVIDER=anthropic
  rem with ANTHROPIC_API_KEY (and ANTHROPIC_MODEL) in ..\.env for Claude, the production model.
  call :load_ai_settings
  call :pick_ai_provider
  rem Voice chat: "server" (speech handled by this service through speaches) when the speaches container
  rem ("docker compose --profile voice up -d") has the speech-to-text model; otherwise "browser" (the browser's own
  rem speech features). Set AI_VOICE_MODE (and AI_VOICE_BASE_URL) to choose yourself.
  call :pick_voice_mode
  rem Knowledge base (RAG, docs\rag\RAG-LOCAL-SETUP.md): on when Elasticsearch answers and Ollama lists the
  rem embedding model (nomic-embed-text); set AI_KNOWLEDGE_ENABLED=true/false to choose yourself.
  call :pick_knowledge_base
  if defined GATEWAY_PORT set "GROCERY_AI_GATEWAY_URL=http://localhost:%GATEWAY_PORT%"
)

echo ============================================================
echo  %SERVICE%
echo  java : %JAVA_EXE%
echo  jar  : %JAR%
echo  profile %SPRING_PROFILES_ACTIVE%, heap %HEAP%   (Ctrl+C stops it)
if /i "%SERVICE%"=="ai-orchestrator-service" echo  AI   : %AI_PROVIDER% %AI_PROVIDER_NOTE%
if /i "%SERVICE%"=="ai-orchestrator-service" echo  Voice: %AI_VOICE_MODE% %AI_VOICE_NOTE%
if /i "%SERVICE%"=="ai-orchestrator-service" echo  Help : knowledge base %AI_KNOWLEDGE_ENABLED% %AI_KNOWLEDGE_NOTE%
if /i "%SERVICE%"=="payment-service" if defined RAZORPAY_KEY_ID echo  Pay  : Razorpay test mode, keys from ..\.env
if /i "%SERVICE%"=="payment-service" if not defined RAZORPAY_KEY_ID echo  Pay  : built-in payment simulator, no Razorpay keys in ..\.env
echo ============================================================
pushd "%SERVICE_DIR%"
"%JAVA_EXE%" -Xmx%HEAP% -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:ReservedCodeCacheSize=64m ^
  -Duser.timezone=UTC -Dfile.encoding=UTF-8 -Dgrocery.host.service=%SERVICE% -jar "%JAR%"
set "EXIT_CODE=%ERRORLEVEL%"
popd
echo.
echo %SERVICE% stopped (exit code %EXIT_CODE%).
exit /b %EXIT_CODE%

:load_ai_settings
rem AI_PROVIDER and the Anthropic key and model from ..\.env (git-ignored). The key is never printed.
if not exist "%~dp0..\.env" goto :eof
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%~dp0..\.env") do (
  if /i "%%A"=="AI_PROVIDER" if not defined AI_PROVIDER set "AI_PROVIDER=%%B"
  if /i "%%A"=="ANTHROPIC_API_KEY" if not defined ANTHROPIC_API_KEY set "ANTHROPIC_API_KEY=%%B"
  if /i "%%A"=="ANTHROPIC_MODEL" if not defined ANTHROPIC_MODEL set "ANTHROPIC_MODEL=%%B"
)
goto :eof

:anthropic_note
set "AI_PROVIDER_NOTE=- Claude %ANTHROPIC_MODEL%, key from ..\.env"
if not defined ANTHROPIC_MODEL set "AI_PROVIDER_NOTE=- Claude claude-opus-5-5, key from ..\.env"
if not defined ANTHROPIC_API_KEY set "AI_PROVIDER_NOTE=- no ANTHROPIC_API_KEY in ..\.env: the assistants answer AI_UNAVAILABLE"
goto :eof

:load_payment_settings
rem The Razorpay TEST keys (and PAYMENT_PROVIDER) from ..\.env, the git-ignored file Docker Compose passes to the
rem container the same way. No key id = the built-in payment simulator. The values are never printed.
if not exist "%~dp0..\.env" goto :eof
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%~dp0..\.env") do (
  if /i "%%A"=="RAZORPAY_KEY_ID" if not defined RAZORPAY_KEY_ID set "RAZORPAY_KEY_ID=%%B"
  if /i "%%A"=="RAZORPAY_KEY_SECRET" if not defined RAZORPAY_KEY_SECRET set "RAZORPAY_KEY_SECRET=%%B"
  if /i "%%A"=="RAZORPAY_WEBHOOK_SECRET" if not defined RAZORPAY_WEBHOOK_SECRET set "RAZORPAY_WEBHOOK_SECRET=%%B"
  if /i "%%A"=="PAYMENT_PROVIDER" if not defined PAYMENT_PROVIDER set "PAYMENT_PROVIDER=%%B"
)
goto :eof

:pick_ai_provider
if /i "%AI_PROVIDER%"=="anthropic" goto :anthropic_note
rem OLLAMA_BASE_URL: the host port the Ollama container publishes (OLLAMA_HOST_PORT may have moved it
rem off 11434), else OLLAMA_HOST_PORT, else 11434. The service reads the same variable.
if defined OLLAMA_BASE_URL goto :ollama_url_known
set "OLLAMA_PORT="
for /f "tokens=1" %%P in ('docker inspect -f "{{range .NetworkSettings.Ports}}{{range .}}{{.HostPort}} {{end}}{{end}}" grocery-ollama-1 2^>nul') do set "OLLAMA_PORT=%%P"
if not defined OLLAMA_PORT set "OLLAMA_PORT=%OLLAMA_HOST_PORT%"
if not defined OLLAMA_PORT set "OLLAMA_PORT=11434"
set "OLLAMA_BASE_URL=http://localhost:%OLLAMA_PORT%"
:ollama_url_known
set "PROBE_MODEL=%OLLAMA_MODEL%"
if not defined PROBE_MODEL set "PROBE_MODEL=qwen3:4b-instruct"
if defined AI_PROVIDER (
  set "AI_PROVIDER_NOTE=- set by AI_PROVIDER; Ollama URL %OLLAMA_BASE_URL%"
  goto :eof
)
rem ollama when Ollama lists the model (OLLAMA_MODEL, or its ":latest" tag), else scripted.
set "AI_PROVIDER=scripted"
set "AI_PROVIDER_NOTE=- no %PROBE_MODEL% at %OLLAMA_BASE_URL%, fixed phrases only. Start it: docker compose --profile ai up -d"
where curl.exe >nul 2>&1
if errorlevel 1 goto :eof
curl.exe -s -m 3 "%OLLAMA_BASE_URL%/api/tags" 2>nul | findstr /i /c:"\"%PROBE_MODEL%\"" /c:"\"%PROBE_MODEL%:latest\"" >nul
if errorlevel 1 goto :eof
set "AI_PROVIDER=ollama"
set "AI_PROVIDER_NOTE=- %PROBE_MODEL% at %OLLAMA_BASE_URL%"
goto :eof

:pick_voice_mode
if defined AI_VOICE_MODE (
  set "AI_VOICE_NOTE=- set by AI_VOICE_MODE %AI_VOICE_BASE_URL%"
  goto :eof
)
set "SPEACHES_PORT="
for /f "tokens=1" %%P in ('docker inspect -f "{{range .NetworkSettings.Ports}}{{range .}}{{.HostPort}} {{end}}{{end}}" grocery-speaches-1 2^>nul') do set "SPEACHES_PORT=%%P"
if not defined SPEACHES_PORT set "SPEACHES_PORT=%SPEACHES_HOST_PORT%"
if not defined SPEACHES_PORT set "SPEACHES_PORT=8000"
if not defined AI_VOICE_TRANSCRIPTION_MODEL set "AI_VOICE_TRANSCRIPTION_MODEL=Systran/faster-whisper-small"
set "AI_VOICE_MODE=browser"
set "AI_VOICE_NOTE=- the browser's own speech features. Server voice: docker compose --profile voice up -d"
where curl.exe >nul 2>&1
if errorlevel 1 goto :eof
curl.exe -s -m 3 "http://localhost:%SPEACHES_PORT%/v1/models" 2>nul | findstr /i /c:"\"%AI_VOICE_TRANSCRIPTION_MODEL%\"" >nul
if errorlevel 1 goto :eof
set "AI_VOICE_MODE=server"
set "AI_VOICE_BASE_URL=http://localhost:%SPEACHES_PORT%/v1"
if not defined AI_VOICE_SPEECH_MODEL set "AI_VOICE_SPEECH_MODEL=speaches-ai/Kokoro-82M-v1.0-ONNX"
if not defined AI_VOICE_SPEECH_VOICE set "AI_VOICE_SPEECH_VOICE=af_heart"
if not defined AI_VOICE_TIMEOUT set "AI_VOICE_TIMEOUT=60s"
set "AI_VOICE_NOTE=- speaches at %AI_VOICE_BASE_URL% (%AI_VOICE_TRANSCRIPTION_MODEL%)"
goto :eof

:pick_knowledge_base
if not defined AI_EMBEDDING_MODEL set "AI_EMBEDDING_MODEL=nomic-embed-text"
if not defined ES_HOST_PORT set "ES_HOST_PORT=9200"
if not defined ELASTICSEARCH_URIS set "ELASTICSEARCH_URIS=http://localhost:%ES_HOST_PORT%"
if defined AI_KNOWLEDGE_ENABLED (
  set "AI_KNOWLEDGE_NOTE=- set by AI_KNOWLEDGE_ENABLED"
  goto :eof
)
set "AI_KNOWLEDGE_ENABLED=false"
set "AI_KNOWLEDGE_NOTE=- off: needs Elasticsearch at %ELASTICSEARCH_URIS% and %AI_EMBEDDING_MODEL% in Ollama (docker compose --profile ai up -d)"
if not defined OLLAMA_BASE_URL goto :eof
where curl.exe >nul 2>&1
if errorlevel 1 goto :eof
curl.exe -s -f -m 3 "%ELASTICSEARCH_URIS%/_cluster/health" >nul 2>&1
if errorlevel 1 goto :eof
curl.exe -s -m 3 "%OLLAMA_BASE_URL%/api/tags" 2>nul | findstr /i /c:"\"%AI_EMBEDDING_MODEL%\"" /c:"\"%AI_EMBEDDING_MODEL%:latest\"" >nul
if errorlevel 1 goto :eof
set "AI_KNOWLEDGE_ENABLED=true"
if not defined AI_KNOWLEDGE_SEED set "AI_KNOWLEDGE_SEED=true"
set "AI_KNOWLEDGE_NOTE=- Elasticsearch %ELASTICSEARCH_URIS%, embeddings %AI_EMBEDDING_MODEL% at %OLLAMA_BASE_URL%"
goto :eof

:find_java
set "JAVA_EXE="
if defined JAVA25_HOME if exist "%JAVA25_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA25_HOME%\bin\java.exe"
if defined JAVA_EXE goto :eof
for /d %%D in ("%ProgramFiles%\Java\jdk-25*" "%ProgramFiles%\Eclipse Adoptium\jdk-25*" "%ProgramFiles%\Microsoft\jdk-25*" "%USERPROFILE%\.jdks\*-25*") do (
  if exist "%%~D\bin\java.exe" set "JAVA_EXE=%%~D\bin\java.exe"
)
if defined JAVA_EXE goto :eof
for /d %%E in ("%USERPROFILE%\.vscode\extensions\redhat.java-*") do (
  for /d %%D in ("%%~E\jre\25*") do if exist "%%~D\bin\java.exe" set "JAVA_EXE=%%~D\bin\java.exe"
)
goto :eof
