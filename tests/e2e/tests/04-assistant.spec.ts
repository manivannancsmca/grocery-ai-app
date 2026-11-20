import { expect, test } from '@playwright/test'
import { ok, until } from '../src/api'
import { signIn } from '../src/ui'
import {
  admin,
  cancelIfOpen,
  createApprovedVendor,
  createCategory,
  createCustomer,
  createProduct,
  offset,
  randomSpot,
  uniqueTag,
} from '../src/world'

/**
 * Journey 4 (contract §4): the customer's shopping assistant with the deterministic `scripted` model
 * (ai-orchestrator-service runs with AI_PROVIDER=scripted): "add 2 …" fills the cart, "checkout" proposes the order
 * as an approval card, and only the customer's confirmation places it.
 */
test.describe('Journey 4 — customer assistant (scripted model)', () => {
  let orderId: string | undefined

  test.afterAll(async () => {
    await cancelIfOpen(orderId)
  })

  test('"add 2 …" → "checkout" → confirm the approval card → the order exists', async ({ page }) => {
    const tag = uniqueTag()
    const storeSpot = randomSpot()
    const productName = `Sona Masuri ${tag}`

    const { store, customer } = await test.step('setup (API): a stocked store and a customer with an address', async () => {
      const status = await ok('GET', '/api/ai/status', { token: (await admin()).token })
      test.skip(status.provider !== 'scripted', `ai-orchestrator-service runs AI_PROVIDER=${status.provider}; this journey needs "scripted"`)
      const categoryId = await createCategory(`E2E Rice ${tag}`)
      const store = await createApprovedVendor(tag, storeSpot, `E2E Assist Mart ${tag}`)
      await createProduct(store, { categoryId, name: productName, sellingPrice: 90, mrp: 110, stock: 30 })
      const customer = await createCustomer(tag, `Kavya ${tag}`, offset(storeSpot, 1, 1))
      return { store, customer }
    })

    await test.step('sign in and open the assistant', async () => {
      await signIn(page, customer.session)
      await page.getByRole('button', { name: 'Open the assistant' }).click()
      await expect(page.getByRole('dialog', { name: 'Shopping assistant' })).toBeVisible()
    })

    const panel = page.getByRole('dialog', { name: 'Shopping assistant' })
    const log = panel.getByRole('log', { name: 'Conversation' })
    const send = async (message: string) => {
      await panel.getByRole('textbox', { name: 'Message the assistant' }).fill(message)
      await panel.getByRole('button', { name: 'Send' }).click()
      await expect(log.getByText(`You: ${message}`, { exact: true })).toBeVisible() // the bubble starts with sr-only "You: "
      // the answer is complete when "Send" comes back (it is "Stop" while streaming)
      await expect(panel.getByRole('button', { name: 'Send' })).toBeVisible({ timeout: 60_000 })
    }

    await test.step(`"add 2 sona masuri ${tag}" fills the cart`, async () => {
      await send(`add 2 sona masuri ${tag}`)
      await expect(log.getByRole('list', { name: 'Tools used' }).last()).toContainText('(Done)')
      await expect(page.getByRole('button', { name: 'Cart, 2 items' })).toBeVisible({ timeout: 20_000 })
      const cart = await ok('GET', '/api/cart', { token: customer.session.token })
      expect(JSON.stringify(cart)).toContain(productName)
    })

    const card = await test.step('"checkout" proposes the order as an approval card; nothing is placed yet', async () => {
      const before = (await ok('GET', '/api/orders?size=100', { token: customer.session.token })).totalElements
      await send('checkout')
      const card = log.getByRole('region', { name: `Place order: 2 items from ${store.name}` })
      await expect(card).toBeVisible()
      await expect(card.getByText('Waiting for approval')).toBeVisible()
      await expect(card).toContainText(productName)
      const after = (await ok('GET', '/api/orders?size=100', { token: customer.session.token })).totalElements
      expect(after).toBe(before)
      return card
    })

    await test.step('confirming the card places the order once', async () => {
      await card.getByRole('button', { name: 'Confirm order' }).click()
      const placed = card.getByText(/^Order GA-[A-Z0-9]+ placed$/)
      await expect(placed).toBeVisible({ timeout: 30_000 })
      await expect(card.getByText('Completed')).toBeVisible()
      const orderNumber = (await placed.textContent())?.match(/GA-[A-Z0-9]+/)?.[0]
      const link = card.getByRole('link', { name: `Open order ${orderNumber}` })
      expect(orderNumber).toBeTruthy()
      const orders = await until('the placed order is listed', async () => {
        const page0 = await ok('GET', '/api/orders?size=100', { token: customer.session.token })
        return page0.content.find((o: { orderNumber: string }) => o.orderNumber === orderNumber)
      })
      orderId = orders.id
      expect(orders.total).toBeGreaterThan(0)
      await link.click()
      await expect(page.getByRole('heading', { level: 1, name: `Order ${orderNumber}` })).toBeVisible()
    })
  })
})
