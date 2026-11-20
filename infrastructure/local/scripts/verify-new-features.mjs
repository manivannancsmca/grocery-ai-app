// =====================================================================================
// Local dev: checks the account and admin features end to end, through the gateway like the web app, with the
// emails read from Mailpit. Everything it changes is put back: passwords, the customer's name, the settings.
//
//   node infrastructure/local/scripts/verify-new-features.mjs
//
// 1. Change password: a wrong current password is refused; a new one works, the old one stops working; the owner
//    gets the "password changed" email. Then back to Dev@12345.
// 2. Forgot / reset password: the same answer for an unknown address; the reset email arrives with a link; the link
//    sets a new password once (a second use is refused). Then back to Dev@12345.
//    identity sends at most 3 reset links per account per hour: run this at most 3 times an hour.
// 3. Profile name: a customer's new name reaches their account (GET /api/auth/me) through customer.events.
// 4. Platform settings: every area answers; an Ops admin changes the review window, a stale version gets 409, the
//    change is in the history, a reset brings the default back; Support may read but not change; a vendor may not
//    read.
// 5. Delivery history filters: status and date range.
// 6. Coupon reporting: the promotions report answers with the coupon name and funding fields.
//
// Prereq: the stack runs with the current code (stop-all, build-jars, start-all), Mailpit on :8025 and the
// development accounts (dev.*@grocery.local). Node 18 or newer.
// Env: GATEWAY_URL (default http://localhost:8080), MAILPIT_URL (default http://localhost:8025).
// =====================================================================================
import { call, GATEWAY_URL, log, login, must, sleep, waitFor } from './gateway-client.mjs'

const MAILPIT_URL = process.env.MAILPIT_URL ?? 'http://localhost:8025'
const PASSWORD = 'Dev@12345'
const TEMPORARY = 'Verify@2026x'
const CUSTOMER = 'dev.customer@grocery.local'

let passed = 0
let failed = 0
function check(label, ok, detail = '') {
  if (ok) passed++
  else failed++
  log(`${ok ? 'PASS' : 'FAIL'}  ${label}${!ok && detail ? ` (${detail})` : ''}`)
}
const errorCode = (result) => result.data?.errorCode ?? result.data?.code ?? result.data?.title

/** Signs in or stops: the account must work with this password. */
async function signIn(email, password = PASSWORD) {
  const token = await login(email, password)
  if (!token) throw new Error(`${email} does not sign in with its password through ${GATEWAY_URL}`)
  return token
}

// ---- Mailpit ------------------------------------------------------------------------------
async function mailpit(path) {
  const response = await fetch(MAILPIT_URL + path)
  if (!response.ok) throw new Error(`Mailpit ${path}: HTTP ${response.status}`)
  return response.json()
}
/** The newest message to `to` whose subject contains `subject`, created after `since` (waits up to 60 s). */
async function waitForEmail(to, subject, since) {
  const deadline = Date.now() + 60_000
  while (Date.now() < deadline) {
    const found = await mailpit(`/api/v1/search?query=${encodeURIComponent(`to:"${to}"`)}&limit=20`)
    const message = (found.messages ?? []).find(
      (m) => m.Subject?.includes(subject) && new Date(m.Created).getTime() >= since,
    )
    if (message) return mailpit(`/api/v1/message/${message.ID}`)
    await sleep(1000)
  }
  return null
}

// ---- 1. change password -------------------------------------------------------------------------
async function changePassword() {
  log('== 1. Change password')
  let token = await signIn(CUSTOMER)
  const wrong = await call('POST', '/api/auth/password/change', token, { currentPassword: 'not-it-1', newPassword: TEMPORARY })
  check('a wrong current password is refused (400 CURRENT_PASSWORD_INCORRECT)',
    wrong.status === 400 && errorCode(wrong) === 'CURRENT_PASSWORD_INCORRECT', `HTTP ${wrong.status} ${errorCode(wrong)}`)

  const since = Date.now() - 2000
  const changed = await call('POST', '/api/auth/password/change', token, { currentPassword: PASSWORD, newPassword: TEMPORARY })
  check('the password changes (204)', changed.status === 204, `HTTP ${changed.status}`)
  check('the old password no longer signs in', (await login(CUSTOMER, PASSWORD)) === null)
  token = await login(CUSTOMER, TEMPORARY)
  check('the new password signs in', token !== null)
  const email = await waitForEmail(CUSTOMER, 'password', since)
  check('the "password changed" email arrived', email !== null && /changed/i.test(email.Subject ?? ''), email?.Subject)

  if (token) {
    const back = await call('POST', '/api/auth/password/change', token, { currentPassword: TEMPORARY, newPassword: PASSWORD })
    check(`back to ${PASSWORD}`, back.status === 204, `HTTP ${back.status}`)
  }
}

// ---- 2. forgot / reset ---------------------------------------------------------------------------
async function resetPassword() {
  log('== 2. Forgot / reset password')
  const unknown = await call('POST', '/api/auth/password/forgot', null, { email: `nobody-${Date.now()}@grocery.local` })
  const since = Date.now() - 2000
  const known = await call('POST', '/api/auth/password/forgot', null, { email: CUSTOMER })
  check('forgot answers 202 for an unknown and a known address alike', unknown.status === 202 && known.status === 202,
    `${unknown.status} / ${known.status}`)

  const email = await waitForEmail(CUSTOMER, 'Reset your', since)
  const text = `${email?.Text ?? ''} ${email?.HTML ?? ''}`
  const match = text.match(/reset-password\?token=([A-Za-z0-9_%.~-]+)/)
  check('the reset email arrived with a link', match !== null,
    email ? 'no link in the email' : 'no email in 60 s (more than 3 reset requests this hour?)')
  if (!match) return
  const resetToken = decodeURIComponent(match[1])

  const reset = await call('POST', '/api/auth/password/reset', null, { token: resetToken, newPassword: TEMPORARY })
  check('the link sets a new password (204)', reset.status === 204, `HTTP ${reset.status} ${errorCode(reset)}`)
  const again = await call('POST', '/api/auth/password/reset', null, { token: resetToken, newPassword: TEMPORARY })
  check('the same link works only once (400 RESET_TOKEN_INVALID)',
    again.status === 400 && errorCode(again) === 'RESET_TOKEN_INVALID', `HTTP ${again.status} ${errorCode(again)}`)
  const token = await login(CUSTOMER, TEMPORARY)
  check('the reset password signs in', token !== null)
  if (token) {
    const back = await call('POST', '/api/auth/password/change', token, { currentPassword: TEMPORARY, newPassword: PASSWORD })
    check(`back to ${PASSWORD}`, back.status === 204, `HTTP ${back.status}`)
  }
}

// ---- 3. profile name -------------------------------------------------------------------------------
async function profileName() {
  log('== 3. Profile name reaches the account')
  const token = await signIn(CUSTOMER)
  const profile = (await must('read the profile', 'GET', '/api/customers/me', token)).data
  const original = { fullName: profile.fullName, phone: profile.phone }
  const renamed = `${original.fullName} Verify`
  await must('rename', 'PUT', '/api/customers/me', token, { ...original, fullName: renamed })
  // identity revokes the old token when the name changes: sign in again for every look.
  const seen = await waitFor('account name', async () => call('GET', '/api/auth/me', await signIn(CUSTOMER)),
    (r) => r.data?.fullName === renamed, 30).catch(() => null)
  check('the new name is the account name (GET /api/auth/me)', seen !== null)

  await must('rename back', 'PUT', '/api/customers/me', await signIn(CUSTOMER), original)
  const restored = await waitFor('account name back', async () => call('GET', '/api/auth/me', await signIn(CUSTOMER)),
    (r) => r.data?.fullName === original.fullName, 30).catch(() => null)
  check(`the name is back to "${original.fullName}"`, restored !== null)
}

// ---- 4. platform settings -----------------------------------------------------------------------
async function platformSettings() {
  log('== 4. Platform settings')
  const ops = await signIn('dev.ops@grocery.local')
  const support = await signIn('dev.support@grocery.local')
  const vendor = await signIn('dev.vendor@grocery.local')
  for (const area of ['delivery', 'grocery-stores', 'restaurants', 'returns', 'reviews', 'delivery-fees']) {
    const result = await call('GET', `/api/admin/settings/${area}`, ops)
    check(`GET /api/admin/settings/${area}`, result.status === 200 && result.data?.values != null, `HTTP ${result.status}`)
  }
  const supportRead = await call('GET', '/api/admin/settings/reviews', support)
  check('Support may read the settings', supportRead.status === 200, `HTTP ${supportRead.status}`)
  const vendorRead = await call('GET', '/api/admin/settings/reviews', vendor)
  check('a vendor may not read them (403)', vendorRead.status === 403, `HTTP ${vendorRead.status}`)

  const before = (await must('read reviews', 'GET', '/api/admin/settings/reviews', ops)).data
  const days = before.values.reviewWindowDays
  const supportWrite = await call('PUT', '/api/admin/settings/reviews', support,
    { values: { reviewWindowDays: days + 1 }, expectedVersion: before.version })
  check('Support may not change them (403)', supportWrite.status === 403, `HTTP ${supportWrite.status}`)

  const saved = await call('PUT', '/api/admin/settings/reviews', ops,
    { values: { reviewWindowDays: days + 1 }, expectedVersion: before.version })
  check(`Ops changes the review window ${days} → ${days + 1}`,
    saved.status === 200 && saved.data?.values?.reviewWindowDays === days + 1, `HTTP ${saved.status}`)
  const stale = await call('PUT', '/api/admin/settings/reviews', ops,
    { values: { reviewWindowDays: days + 2 }, expectedVersion: before.version })
  check('a save from a stale version is refused (409 SETTINGS_CHANGED)',
    stale.status === 409 && errorCode(stale) === 'SETTINGS_CHANGED', `HTTP ${stale.status} ${errorCode(stale)}`)
  const history = await call('GET', '/api/admin/settings/reviews/history?size=5', ops)
  const entries = history.data?.content ?? []
  check('the change is in the history', history.status === 200 && entries.some((e) => e.field === 'reviewWindowDays'),
    `HTTP ${history.status}`)

  // Back to what it was: the default when nothing was overridden before, else the earlier value.
  const wasOverridden = (before.overridden ?? []).includes('reviewWindowDays')
  const current = saved.data?.version ?? before.version
  const restored = await call('PUT', '/api/admin/settings/reviews', ops,
    { values: { reviewWindowDays: wasOverridden ? days : null }, expectedVersion: current })
  check(`the review window is back to ${days}${wasOverridden ? '' : ' (the default)'}`,
    restored.status === 200 && restored.data?.values?.reviewWindowDays === days, `HTTP ${restored.status}`)
}

// ---- 5. delivery history filters --------------------------------------------------------------------
async function deliveryHistory() {
  log('== 5. Delivery history filters')
  const partner = await signIn('dev.delivery@grocery.local')
  for (const status of ['DELIVERED', 'FAILED']) {
    const page = await call('GET', `/api/delivery/history?status=${status}&size=50`, partner)
    const rows = page.data?.content ?? []
    check(`history ?status=${status} (${rows.length} trips)`,
      page.status === 200 && rows.every((row) => row.status === status), `HTTP ${page.status}`)
  }
  const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' })
  const ranged = await call('GET', `/api/delivery/history?from=${today}&to=${today}&size=50`, partner)
  check(`history ?from=${today}&to=${today}`, ranged.status === 200, `HTTP ${ranged.status}`)
  const bad = await call('GET', '/api/delivery/history?status=LOST', partner)
  check('an unknown status is refused (400)', bad.status === 400, `HTTP ${bad.status}`)
}

// ---- 6. coupon reporting -------------------------------------------------------------------------
async function couponReporting() {
  log('== 6. Coupon reporting')
  const ops = await signIn('dev.ops@grocery.local')
  const overview = await call('GET', '/api/admin/reports/overview', ops)
  check('the overview report answers with coupon funding fields',
    overview.status === 200 && overview.data?.couponDiscounts && 'platform' in overview.data.couponDiscounts,
    `HTTP ${overview.status}`)
  const promotions = await call('GET', '/api/admin/reports/promotions', ops)
  check('the promotions report answers', promotions.status === 200, `HTTP ${promotions.status}`)
}

for (const step of [changePassword, resetPassword, profileName, platformSettings, deliveryHistory, couponReporting]) {
  try {
    await step()
  } catch (error) {
    failed++
    log(`FAIL  ${step.name} stopped: ${error.message}`)
  }
}
log(`\n${passed} passed, ${failed} failed`)
process.exit(failed > 0 ? 1 : 0)
