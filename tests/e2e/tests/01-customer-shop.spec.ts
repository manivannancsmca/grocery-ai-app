import { expect, test } from '@playwright/test'
import { ok, PASSWORD, until } from '../src/api'
import { rupees } from '../src/ui'
import {
  cancelIfOpen,
  createApprovedVendor,
  createCategory,
  createProduct,
  createStoreCoupon,
  offset,
  randomSpot,
  testEmail,
  uniqueTag,
} from '../src/world'

/**
 * Journey 1 (contract §4): a new customer registers, adds an address, searches, fills the cart, applies a coupon,
 * pays through the payment simulator and sees the order change live (no reload) when the store accepts it.
 */
test.describe('Journey 1 — customer shops and pays', () => {
  let orderId: string | undefined

  test.afterAll(async () => {
    await cancelIfOpen(orderId)
  })

  test('register → address → search → cart → coupon → simulator payment → live order', async ({ page }) => {
    const tag = uniqueTag()
    const storeSpot = randomSpot()
    const home = offset(storeSpot, 1.2, 0.6)
    const productName = `Basmati ${tag}`
    const couponCode = `E2E${tag.toUpperCase()}`.slice(0, 20)
    const customerName = `Asha ${tag}`
    const customerEmail = testEmail('shopper', tag)

    const store = await test.step('setup (API): an approved store with a stocked product and a ₹20 coupon', async () => {
      const categoryId = await createCategory(`E2E Staples ${tag}`)
      const vendor = await createApprovedVendor(tag, storeSpot)
      await createProduct(vendor, { categoryId, name: productName, sellingPrice: 100, mrp: 120, stock: 25 })
      await createStoreCoupon(vendor, couponCode, 20, 150)
      return vendor
    })

    await test.step('register as a customer', async () => {
      await page.goto('/register')
      await expect(page.getByRole('radio', { name: /customer/i })).toBeChecked()
      await page.getByLabel('Full name').fill(customerName)
      await page.getByLabel(/^Email/).fill(customerEmail)
      await page.getByLabel('Mobile number').fill('+919876543210')
      await page.getByLabel(/^Password/).fill(PASSWORD)
      await page.getByLabel('Confirm password').fill(PASSWORD)
      await page.getByRole('button', { name: 'Create customer account' }).click()
      await expect(page.getByRole('button', { name: `Account menu for ${customerName}` })).toBeVisible()
    })

    await test.step('add a delivery address', async () => {
      await page.goto('/account/addresses')
      await expect(page.getByRole('heading', { level: 1, name: 'Delivery addresses' })).toBeVisible()
      // The profile is created asynchronously after registration: the empty state appears once it exists.
      await page.getByRole('button', { name: /^Add (your first )?address$/ }).first().click()
      const dialog = page.getByRole('dialog', { name: 'Add a delivery address' })
      await dialog.getByLabel(/^Label/).fill('Home')
      await dialog.getByLabel('Recipient name').fill(customerName)
      await dialog.getByLabel(/^Phone/).fill('+919876543210')
      await dialog.getByLabel('House / flat, street').fill('12 Lake View Layout')
      await dialog.getByLabel('City').fill('Hubballi')
      await dialog.getByLabel(/^State/).fill('Karnataka')
      await dialog.getByLabel('PIN code').fill('580020')
      await dialog.getByLabel('Latitude').fill(String(home.latitude))
      await dialog.getByLabel('Longitude').fill(String(home.longitude))
      await dialog.getByRole('button', { name: 'Add address' }).click()
      await expect(page.getByText('Address added.')).toBeVisible()
      await expect(page.getByRole('article', { name: 'Home address' })).toBeVisible()
    })

    await test.step('search for the product and add 2 to the cart', async () => {
      const search = page.getByRole('combobox', { name: 'Search products' })
      await search.fill(productName)
      await search.press('Enter')
      await expect(page).toHaveURL(/\/search\?q=/)
      const results = page.getByRole('list', { name: 'Products' })
      await results.getByRole('button', { name: `Add ${productName} to cart` }).click()
      await expect(page.getByRole('button', { name: 'Cart, 1 item' })).toBeVisible()
      await results.getByRole('button', { name: `Increase quantity of ${productName}` }).click()
      await expect(page.getByRole('button', { name: 'Cart, 2 items' })).toBeVisible()
    })

    await test.step('open the cart and go to checkout', async () => {
      await page.getByRole('button', { name: 'Cart, 2 items' }).click()
      const cart = page.getByRole('dialog', { name: 'Your cart' })
      await expect(cart.getByRole('list', { name: 'Items in your cart' })).toContainText(productName)
      await cart.getByRole('link', { name: 'Checkout' }).click()
      await expect(page.getByRole('heading', { level: 1, name: 'Checkout' })).toBeVisible()
      await expect(page.getByRole('radio', { name: /^Home/ })).toBeChecked()
    })

    const total = await test.step('apply the store coupon', async () => {
      const offers = page.getByRole('region', { name: 'Offers & coupons' })
      await offers.getByRole('textbox', { name: 'Coupon code' }).fill(couponCode.toLowerCase())
      await offers.getByRole('button', { name: 'Apply', exact: true }).click()
      await expect(page.getByText(`Coupon ${couponCode} applied.`)).toBeVisible()
      await expect(page.getByRole('region', { name: 'Order summary' })).toContainText(`Coupon ${couponCode}`)
      // The preview the button shows is the server's: subtotal ₹200 + delivery fee − ₹20.
      const preview = await until('the checkout preview with the coupon', async () => {
        const body = await ok('GET', '/api/cart/preview', { token: await customerToken() })
        return body.coupon?.code === couponCode ? body : null
      })
      expect(Number(preview.discount)).toBe(20)
      return Number(preview.total)
    })

    await test.step('place the order', async () => {
      const place = page.getByRole('button', { name: `Place order · ${rupees(total)}` })
      await expect(place).toBeEnabled()
      await place.click()
      await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}$/)
      await expect(page.getByRole('heading', { level: 1, name: /^Order GA-/ })).toBeVisible()
      orderId = page.url().split('/').pop()
    })

    await test.step('pay through the simulator', async () => {
      await page.getByRole('button', { name: `Pay now · ${rupees(total)}` }).click()
      const dialog = page.getByRole('dialog', { name: /Test payment/ })
      await dialog.getByRole('button', { name: 'Pay successfully' }).click()
      await expect(page.getByRole('main').getByRole('status').filter({ hasText: 'Order confirmed' })).toBeVisible({ timeout: 30_000 })
    })

    await test.step('the order updates live when the store accepts it', async () => {
      await expect(page.getByRole('img', { name: 'Live updates on' })).toBeVisible()
      // The store accepts through the API; the open page must change without a reload (SSE → refetch).
      await ok('POST', `/api/vendor/orders/${orderId}/accept`, { token: store.session.token, body: { readyInMinutes: 15 } })
      await expect(page.getByRole('main').getByRole('status').filter({ hasText: 'Accepted — ready in about 15 min' })).toBeVisible({ timeout: 30_000 })
      const history = page.getByRole('list', { name: 'Status history' })
      await expect(history).toContainText('Accepted')
    })

    // The customer's token for API cross-checks: the app keeps its own in memory, so log in once through the API.
    async function customerToken(): Promise<string> {
      if (!cachedToken) cachedToken = (await ok('POST', '/api/auth/login', { body: { email: customerEmail, password: PASSWORD } })).accessToken
      return cachedToken as string
    }
  })
})

let cachedToken: string | undefined
