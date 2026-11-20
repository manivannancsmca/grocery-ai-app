// Screenshot a page of the running FreshCart web app (http://localhost:6420), signed in as a dev account or not.
//
//   node screenshot.mjs --as vendor --path /vendor/dashboard --out ./shots --name vendor-dashboard
//   node screenshot.mjs --as guest --path /login --width 390 --height 844 --name login-phone
//   node screenshot.mjs --as customer --menu --width 390 --height 844 --name customer-phone-menu
//
// Options (all optional):
//   --as      guest | customer | vendor | restaurant | delivery | admin | ops | finance | support | delivery-manager
//             (default guest; the dev accounts are seeded when DEV_SEED_ENABLED=true)
//   --path    page to open after signing in (default: the workspace home the sign-in lands on)
//   --name    file name without .png (default: <as>)
//   --out     folder for the PNG (default: current folder)
//   --width / --height   viewport (default 1440 x 900)
//   --menu    on phone widths, open the hamburger menu before the shot
//   --full    full-page screenshot instead of the viewport
//   --wait    text that must be visible before the shot
//   --base    app URL (default http://localhost:6420)
//
// Prints JSON: the file, the final URL, and console errors (401s from /api/auth/refresh on signed-out pages are
// expected and filtered out). Exit code 1 when the page could not be reached or signed in.
import { chromium } from 'playwright-core'

const ACCOUNTS = {
  customer: 'dev.customer@grocery.local',
  vendor: 'dev.vendor@grocery.local',
  restaurant: 'dev.restaurant@grocery.local',
  delivery: 'dev.delivery@grocery.local',
  admin: 'admin@grocery.local',
  ops: 'dev.ops@grocery.local',
  finance: 'dev.finance@grocery.local',
  support: 'dev.support@grocery.local',
  'delivery-manager': 'dev.delivery-manager@grocery.local',
}
const PASSWORDS = { admin: 'Admin@12345' }
const DEV_PASSWORD = 'Dev@12345'

function option(name, fallback) {
  const index = process.argv.indexOf(`--${name}`)
  if (index < 0) return fallback
  const value = process.argv[index + 1]
  return value === undefined || value.startsWith('--') ? true : value
}

const as = option('as', 'guest')
const base = option('base', 'http://localhost:6420')
const out = option('out', '.')
const name = option('name', as)
const width = Number(option('width', 1440))
const height = Number(option('height', 900))
const errors = []

if (as !== 'guest' && !ACCOUNTS[as]) {
  console.error(`Unknown --as ${as}. One of: guest, ${Object.keys(ACCOUNTS).join(', ')}`)
  process.exit(2)
}

// Edge ships with Windows, so no browser download is needed; set BROWSER_CHANNEL=chrome (or chromium) elsewhere.
const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL ?? 'msedge', headless: true })
try {
  const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1 })
  const page = await context.newPage()
  page.on('console', (m) => {
    if (m.type() === 'error' && !/status of 401/.test(m.text())) errors.push(m.text())
  })
  page.on('pageerror', (e) => errors.push(e.message))

  if (as !== 'guest') {
    await page.goto(`${base}/login`)
    await page.getByLabel('Email').fill(ACCOUNTS[as])
    // exact: the field's "Show password" button is labelled too.
    await page.getByLabel('Password', { exact: true }).fill(PASSWORDS[as] ?? DEV_PASSWORD)
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    // Signed in = the page left /login (the workspace home differs per role).
    await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 30000 })
  }
  // "login" works as well as "/login" (Git Bash turns a leading "/" into a Windows path without MSYS_NO_PATHCONV=1).
  const path = option('path', as === 'guest' ? '/' : null)
  if (typeof path === 'string') {
    if (/^[A-Za-z]:[\\/]/.test(path)) throw new Error(`--path ${path} looks like a Windows path: run with MSYS_NO_PATHCONV=1 or drop the leading /`)
    await page.goto(`${base}/${path.replace(/^\/+/, '')}`)
  }
  await page.waitForLoadState('networkidle').catch(() => {})
  const wait = option('wait', null)
  if (typeof wait === 'string') await page.getByText(wait).first().waitFor({ timeout: 30000 })
  if (option('menu', false) === true) {
    await page.getByRole('button', { name: 'Open menu' }).click()
  }
  await page.waitForTimeout(800) // entrance animations (0.2–0.45 s)

  const file = `${out}/${name}.png`
  await page.screenshot({ path: file, fullPage: option('full', false) === true })
  console.log(JSON.stringify({ file, url: page.url(), errors }, null, 2))
} catch (error) {
  console.error(`Screenshot failed: ${error.message}`)
  process.exitCode = 1
} finally {
  await browser.close()
}
