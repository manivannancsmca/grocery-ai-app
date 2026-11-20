#!/usr/bin/env bash
# =====================================================================================================================
# Runs the Phase 13 Playwright journeys (Chromium) in the official Playwright image against the running local stack.
#   bash tests/e2e/run-e2e.sh                       # every journey
#   bash tests/e2e/run-e2e.sh tests/02-*.spec.ts    # one journey (any `playwright test` arguments)
# The container joins the compose network (grocery_default): the API is http://api-gateway:8080 and the browser opens
# http://localhost:6420, which scripts/tcp-forward.mjs forwards to frontend:6420 (see that file for why).
# Environment: E2E_NETWORK, APP_URL, FRONTEND_UPSTREAM, API_URL, E2E_WORKERS (1), E2E_RETRIES (0), E2E_MEMORY (2g),
#              DOCKER (the docker command; e.g. a wrapper that throttles heavy containers).
# Results: tests/e2e/playwright-report/index.html, tests/e2e/test-results/{results.json,junit.xml,artifacts/}
# =====================================================================================================================
set -euo pipefail
cd "$(dirname "$0")"
E2E_DIR="$(pwd -W 2>/dev/null || pwd)"          # a Windows path under Git Bash, a POSIX path elsewhere
PLAYWRIGHT_VERSION=1.64.1                       # keep equal to @playwright/test in package.json
IMAGE="mcr.microsoft.com/playwright:v${PLAYWRIGHT_VERSION}-noble"
DOCKER="${DOCKER:-docker}"

# shellcheck disable=SC2086  # DOCKER may be a command with arguments
MSYS_NO_PATHCONV=1 $DOCKER run --rm --init --ipc=host --memory "${E2E_MEMORY:-2g}" \
  --network "${E2E_NETWORK:-grocery_default}" \
  -v "$E2E_DIR:/work" -v grocery-e2e-node-modules:/work/node_modules -w /work \
  -e APP_URL="${APP_URL:-http://localhost:6420}" \
  -e FRONTEND_UPSTREAM="${FRONTEND_UPSTREAM-frontend:6420}" \
  -e API_URL="${API_URL:-http://api-gateway:8080}" \
  -e E2E_WORKERS -e E2E_RETRIES -e E2E_RUN_ID -e CI -e NPM_CONFIG_UPDATE_NOTIFIER=false \
  "$IMAGE" sh -c 'npm ci --no-audit --no-fund --loglevel=error && npx playwright test "$@"' playwright "$@"
