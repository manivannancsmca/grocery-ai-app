import { defineConfig, devices } from '@playwright/test'

/**
 * Phase 13 end-to-end journeys (contract docs/api/phase-13-observability-and-testing.md §4).
 *
 *   APP_URL            the frontend as the browser sees it (default http://localhost:6420)
 *   API_URL            the API gateway, for fixtures that set up data through the API (default http://localhost:8090)
 *   FRONTEND_UPSTREAM  optional host:port of the real frontend (e.g. frontend:6420 inside the compose network); when
 *                      set, scripts/tcp-forward.mjs serves APP_URL's port locally and forwards to it (see that file)
 *   E2E_WORKERS        parallel workers (default 1: a laptop runs the whole platform next to the browser)
 *   E2E_RETRIES        retries per test (default 0, so flakiness shows up in the report)
 */
const APP_URL = process.env.APP_URL ?? 'http://localhost:6420'
const FRONTEND_UPSTREAM = process.env.FRONTEND_UPSTREAM

export default defineConfig({
  testDir: './tests',
  // A journey drives several roles through an event-driven backend (Kafka + Debezium): give it room.
  timeout: 300_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  workers: Number(process.env.E2E_WORKERS ?? 1),
  retries: Number(process.env.E2E_RETRIES ?? 0),
  outputDir: 'test-results/artifacts',
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['json', { outputFile: 'test-results/results.json' }],
    ['junit', { outputFile: 'test-results/junit.xml' }],
  ],
  use: {
    baseURL: APP_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 20_000,
    navigationTimeout: 45_000,
    locale: 'en-IN',
    timezoneId: 'Asia/Kolkata',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } }],
  webServer: FRONTEND_UPSTREAM
    ? {
        command: 'node scripts/tcp-forward.mjs',
        url: APP_URL,
        reuseExistingServer: false,
        timeout: 60_000,
        stdout: 'pipe',
      }
    : undefined,
})
