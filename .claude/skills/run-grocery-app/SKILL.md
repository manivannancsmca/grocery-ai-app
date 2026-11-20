---
name: run-grocery-app
description: Run, restart and screenshot the FreshCart grocery app on this Windows machine (Spring services on the host, infrastructure and the Vite frontend in Docker). Use to see a UI change in the real app, sign in as a dev account, take desktop/phone screenshots, restart one backend service after a code change, or run the frontend checks.
---

# Run and drive the FreshCart app

How the stack runs here (Windows 11, Git Bash + PowerShell, Docker Desktop):

| Part | Where | URL |
|---|---|---|
| React app (Vite dev server, hot reload) | Docker `grocery-frontend-1`, source bind-mounted from `frontend/grocery-web` | http://localhost:6420 |
| API gateway + 16 Spring Boot services | host JVMs, one Windows Terminal tab each (`infrastructure/local/host-run/start-all.cmd`) | gateway http://localhost:8080 |
| MySQL, Kafka, Redis, Elasticsearch, Mailpit, Ollama, ... | Docker Compose project `grocery` (`infrastructure/local/docker-compose.yml`) | Mailpit http://localhost:8025 |

## 1. Is it up?

```bash
curl -s -o /dev/null -w "frontend %{http_code}\n" http://localhost:6420
curl -s -o /dev/null -w "gateway %{http_code}\n" "http://localhost:8080/api/search/products?size=1"
docker ps --format '{{.Names}}\t{{.Status}}' | grep grocery-
```

Nothing answering: `infrastructure\local\host-run\start-all.cmd` (stops the Spring containers, starts the
infrastructure, opens one tab per service; give it 2 minutes). Ollama is not part of it:
`docker compose --profile ai up -d` in `infrastructure/local` first if the AI assistants should use a real model.

## 2. Frontend changes

Nothing to restart: Vite in `grocery-frontend-1` hot-reloads files saved under `frontend/grocery-web/src`.
Refresh the browser (or take a new screenshot, section 4).

## 3. Backend changes: rebuild and restart ONE service

```powershell
# 1. stop it (find the JVM by its marker, then its tab's cmd.exe)
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*grocery.host.service=<service>*' } | Select-Object ProcessId, ParentProcessId
Stop-Process -Id <java pid> -Force; Stop-Process -Id <parent cmd pid> -Force   # closes the old tab
# 2. rebuild the jar (Maven in Docker with the grocery-m2 volume)
& "infrastructure\local\host-run\build-jars.cmd" <service>
# 3. start it in a new tab of the services window
Start-Process wt.exe -ArgumentList @('-w','grocery-services','new-tab','--title','<service>','--suppressApplicationTitle',
  '-d','"D:\Drive E\dev\grocery-ai-app\infrastructure\local\host-run"','cmd','/k','.\run-service.cmd','<service>')
```

Then wait for `http://localhost:<port>/actuator/health` to say UP (ports: `server.port` in the service's
`application.yml`; ai-orchestrator-service is 8115).

- **Stop the JVM before building**: a running service locks its jar and the Spring Boot repackage step fails with
  `Unable to rename ... .jar.original`. Kill the tab's `cmd.exe` right after the JVM, or `cmd /k` may already have
  started a new JVM from the old tab. Re-check for a second `java.exe` with the same marker before rebuilding.
- ai-orchestrator-service: `run-service.cmd` picks `AI_PROVIDER=ollama` when the `grocery-ollama-1` container lists
  the model (it reads the container's host port, 11435 here, not 11434), else the fixed-phrase `scripted` stand-in.
  The tab's banner line `AI :` says which.

## 4. Screenshots (headless Edge, signed in as a dev account)

`scripts/screenshot.mjs` drives the real app with `playwright-core` and the Edge that ships with Windows (no browser
download). Set it up once per scratch folder, then call it per screen:

```bash
S="<scratchpad>/shots"   # any folder outside the repo
mkdir -p "$S" && cp "D:/Drive E/dev/grocery-ai-app/.claude/skills/run-grocery-app/scripts/screenshot.mjs" "$S/"
cd "$S" && { [ -d node_modules/playwright-core ] || { npm init -y >/dev/null; npm i --no-audit --no-fund playwright-core@1.55.0; }; }

node screenshot.mjs --as guest --path login --name login                      # signed out
node screenshot.mjs --as vendor --name vendor                                  # lands on the workspace home
node screenshot.mjs --as admin --path admin/orders --name admin-orders
node screenshot.mjs --as customer --menu --width 390 --height 844 --name customer-phone-menu
```

Then **look at every PNG** (Read tool). The script prints `{file, url, errors}`; `errors` are page console errors
(the signed-out 401s from `/api/auth/refresh` are filtered out as expected). Options: `--as`, `--path`, `--name`,
`--out`, `--width`, `--height`, `--menu`, `--full`, `--wait <text>`, `--base`; see the script header.

- **Git Bash mangles `--path /login`** into `C:/.../Git/login`. Write `--path login`, or prefix the command with
  `MSYS_NO_PATHCONV=1` (the script refuses a mangled path with that hint).
- Dev accounts (seeded when `DEV_SEED_ENABLED=true`, listed in `frontend/grocery-web/src/features/auth/devAccounts.ts`):
  `customer`, `vendor`, `restaurant`, `delivery`, `ops`, `finance`, `support`, `delivery-manager` use `Dev@12345`;
  `admin` (`admin@grocery.local`) uses `Admin@12345`. Fresh from identity-service the vendor, restaurant and delivery
  accounts land on onboarding screens; after `node infrastructure/local/scripts/seed-chennai.mjs` they are approved
  Velachery businesses (Vijaya Nagar Fresh Mart, Kaveri Meals & Tiffin, partner Suresh Kannan) and `customer` is Kavya
  Sriram with three Chennai addresses. `verify-workflow.mjs` (same folder) runs whole orders through the four.
- **Shopping data:** `node infrastructure/local/scripts/seed-catalog.mjs` loads the grocery catalog: 30 categories
  and 224 products with photos, prices and stock. It is safe to re-run. The products are sold by
  `dev.store@grocery.local` / `Dev@12345` (FreshCart Store, an approved vendor). The script isn't a `--as` option:
  sign in as that store by hand in a copy of the script if you need its workspace.
- The sign-in waits for the URL to leave `/login`; at phone widths the left menu is hidden until `--menu`.
- Computed colours for a style change: `await page.locator('header').evaluate(el => getComputedStyle(el).backgroundColor)`
  in a copy of the script.

## 5. Frontend checks

`node_modules` lives in the container's volume (`grocery_frontend-node-modules`), not on the host:

```bash
docker exec grocery-frontend-1 sh -c 'cd /app && npx eslint <files> && npx vitest run src/features/auth'
```

`npx tsc -b` runs out of memory inside `grocery-frontend-1` (800 MB limit); run it in a throwaway container instead:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -m 3g -e NODE_OPTIONS=--max-old-space-size=2560 \
  -v "D:\\Drive E\\dev\\grocery-ai-app\\frontend\\grocery-web:/app" -v grocery_frontend-node-modules:/app/node_modules \
  -w /app --entrypoint sh grocery/grocery-web:dev -c 'npx tsc -b'
```

Backend tests: see the Maven-in-Docker command in `infrastructure/local/host-run/build-jars.cmd`; for integration
tests add `-v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` and
`mvn test` instead of `package`.
