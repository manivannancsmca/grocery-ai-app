import { type Browser, type BrowserContext, expect, type Page } from '@playwright/test'
import type { Session } from './api'

/** Signs in through the login page (the refresh cookie and in-memory access token are the app's own). */
export async function signIn(page: Page, session: Pick<Session, 'email' | 'password' | 'fullName'>): Promise<void> {
  await page.goto('/login')
  await page.getByLabel(/^Email/).fill(session.email)
  await page.getByLabel(/^Password/).fill(session.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).not.toHaveURL(/\/login(\?|$)/)
  await expect(page.getByRole('button', { name: /Account menu for/ })).toBeVisible()
}

/** A separate browser context (own cookies) for a second role in the same journey. */
export async function newRoleContext(
  browser: Browser,
  baseURL: string | undefined,
  options: { geolocation?: { latitude: number; longitude: number } } = {},
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({
    baseURL,
    locale: 'en-IN',
    timezoneId: 'Asia/Kolkata',
    viewport: { width: 1440, height: 900 },
    ...(options.geolocation ? { geolocation: options.geolocation, permissions: ['geolocation'] } : {}),
  })
  return { context, page: await context.newPage() }
}

/** Formats rupees the way the UI does: ₹285, ₹260.20, ₹2,500. */
export function rupees(amount: number): string {
  const fixed = Number.isInteger(amount) ? amount.toString() : amount.toFixed(2)
  const [whole, fraction] = fixed.split('.')
  const grouped = Number(whole).toLocaleString('en-IN')
  return `₹${grouped}${fraction ? `.${fraction}` : ''}`
}
