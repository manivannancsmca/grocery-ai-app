@echo off
rem ============================================================================================
rem Stops every Spring Boot service that run-service.cmd started on this machine.
rem (They are recognised by the -Dgrocery.host.service=<name> flag; nothing else is touched.)
rem It also closes their tabs (the "cmd /k .\run-service.cmd <name>" shells start-all.cmd opened): a shell left open
rem would go on reading run-service.cmd where it stopped, and if the file was edited meanwhile it could start the
rem service again. start-all.cmd opens fresh tabs.
rem The Docker infrastructure keeps running: "docker compose --profile all stop" stops that too.
rem ============================================================================================
setlocal EnableExtensions
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$found = Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -match '-Dgrocery\.host\.service=(\S+)' };" ^
  "if (-not $found) { 'No host-run service is running.' };" ^
  "foreach ($p in $found) { $null = $p.CommandLine -match '-Dgrocery\.host\.service=(\S+)'; Stop-Process -Id $p.ProcessId -Force; 'stopped ' + $Matches[1] };" ^
  "$shells = Get-CimInstance Win32_Process -Filter \"Name='cmd.exe'\" | Where-Object { $_.CommandLine -match '^cmd /k \.\\run-service\.cmd [a-z-]+\s*$' };" ^
  "foreach ($s in $shells) { Stop-Process -Id $s.ProcessId -Force -ErrorAction SilentlyContinue };" ^
  "if ($shells) { 'closed ' + @($shells).Count + ' service tabs' }"
