import { expect, type Locator, test } from '@playwright/test'
import { call, until } from '../src/api'
import { newRoleContext, signIn } from '../src/ui'
import {
  cancelIfOpen,
  createApprovedPartner,
  createApprovedVendor,
  createCategory,
  createCustomer,
  createProduct,
  offset,
  orderOf,
  placePaidOrder,
  randomSpot,
  uniqueTag,
} from '../src/world'

/**
 * Journey 2 (contract §4): the vendor accepts and packs a paid order, a delivery partner picks it up and delivers it
 * (the partner app's location simulator drives towards the customer), the customer follows it live and rates it.
 * Three browser contexts — vendor, partner, customer — run side by side; the order itself is placed and paid
 * through the API (journey 1 covers that UI).
 */
test.describe('Journey 2 — fulfil, deliver, rate', () => {
  let orderId: string | undefined

  test.afterAll(async () => {
    await cancelIfOpen(orderId) // only does something if the journey stopped half-way
  })

  test('vendor accepts and packs; partner picks up and delivers; customer rates the order', async ({ page, browser, baseURL }) => {
    const tag = uniqueTag()
    const storeSpot = randomSpot()
    const home = offset(storeSpot, 1.5, 0.5)
    const productName = `Toor Dal ${tag}`
    const riderFirstName = `Ravi${tag.slice(-3)}`

    const { store, partner, customer, order } = await test.step('setup (API): store, product, partner, customer, a paid order', async () => {
      const categoryId = await createCategory(`E2E Pulses ${tag}`)
      const store = await createApprovedVendor(tag, storeSpot, `E2E Fresh ${tag}`)
      const productId = await createProduct(store, { categoryId, name: productName, sellingPrice: 150, mrp: 180, stock: 20 })
      const partner = await createApprovedPartner(tag, riderFirstName)
      const customer = await createCustomer(tag, `Meera ${tag}`, home)
      const order = await placePaidOrder(customer, productId, 1)
      orderId = order.orderId
      return { store, partner, customer, order }
    })

    // The partner's browser reports the device location (Playwright geolocation) at the store.
    const partnerApp = await newRoleContext(browser, baseURL, { geolocation: storeSpot })
    const vendorApp = await newRoleContext(browser, baseURL)
    try {
      await test.step('partner signs in and goes online at the store', async () => {
        await signIn(partnerApp.page, partner.session)
        await partnerApp.page.goto('/delivery')
        const online = partnerApp.page.getByRole('switch', { name: 'Online' })
        await expect(online).toHaveAttribute('aria-checked', 'false')
        await online.click()
        await expect(online).toHaveAttribute('aria-checked', 'true')
        await expect(partnerApp.page.getByText(/Sharing your location · last sent/)).toBeVisible({ timeout: 30_000 })
        await expect(partnerApp.page.getByText(/No offers right now/)).toBeVisible()
      })

      await test.step('customer opens the order page', async () => {
        await signIn(page, customer.session)
        await page.goto(`/orders/${order.orderId}`)
        await expect(page.getByRole('heading', { level: 1, name: `Order ${order.orderNumber}` })).toBeVisible()
        await expect(page.getByRole('main').getByRole('status').filter({ hasText: 'Order confirmed' })).toBeVisible()
      })

      await test.step('vendor accepts the order and starts packing', async () => {
        await signIn(vendorApp.page, store.session)
        await vendorApp.page.goto('/vendor/orders')
        const card = vendorApp.page.getByRole('region', { name: 'New' }).getByRole('article', { name: `Order ${order.orderNumber}` })
        await expect(card).toBeVisible({ timeout: 30_000 })
        await expect(card.getByLabel('Ready in')).toHaveValue('15')
        // The very first click on the page is "Accept": it must still land, even though it also unlocks the sound
        // alerts and removes the "Click to enable sound alerts" button (regression found by this journey).
        await card.getByRole('button', { name: 'Accept' }).click()
        const accepted = vendorApp.page.getByRole('region', { name: 'Accepted' }).getByRole('article', { name: `Order ${order.orderNumber}` })
        await accepted.getByRole('button', { name: 'Start packing' }).click()
        await expect(
          vendorApp.page.getByRole('region', { name: 'Packing' }).getByRole('article', { name: `Order ${order.orderNumber}` }),
        ).toBeVisible()
        // live on the customer's page
        await expect(page.getByRole('main').getByRole('status').filter({ hasText: 'Packing your order' })).toBeVisible({ timeout: 30_000 })
      })

      await test.step('partner gets the offer and accepts it', async () => {
        const offer = partnerApp.page.getByRole('article', { name: `Delivery offer from ${store.name}` })
        await expect(offer).toBeVisible({ timeout: 45_000 })
        await offer.getByRole('button', { name: 'Accept' }).click()
        await expect(partnerApp.page.getByText(`Delivery accepted — head to ${store.name}`)).toBeVisible()
        const active = partnerApp.page.getByRole('region', { name: 'Active delivery' })
        await expect(active.getByRole('heading', { name: 'Go to the store' })).toBeVisible()
        await expect(active.getByRole('button', { name: 'Picked up' })).toBeDisabled()
      })

      await test.step('vendor marks the order ready for pickup', async () => {
        const packing = vendorApp.page.getByRole('region', { name: 'Packing' }).getByRole('article', { name: `Order ${order.orderNumber}` })
        await packing.getByRole('button', { name: 'Ready for pickup' }).click()
        await expect(
          vendorApp.page.getByRole('region', { name: 'Ready' }).getByRole('article', { name: `Order ${order.orderNumber}` }),
        ).toBeVisible()
        await expect(page.getByRole('main').getByRole('status').filter({ hasText: 'Ready for pickup' })).toBeVisible({ timeout: 30_000 })
      })

      await test.step('partner switches to the location simulator, stands at the store and picks the order up', async () => {
        const active = partnerApp.page.getByRole('region', { name: 'Active delivery' })
        await expect(active.getByText('Order ready')).toBeVisible({ timeout: 30_000 })
        const simulator = partnerApp.page.getByRole('region', { name: 'Location simulator' })
        await simulator.getByRole('switch', { name: 'Use simulated location' }).click()
        await simulator.getByRole('button', { name: 'At the store' }).click()
        await expect(simulator.getByText(/Simulated position:/)).toContainText(storeSpot.latitude.toFixed(6))
        await active.getByRole('button', { name: 'Picked up' }).click()
        await expect(active.getByRole('heading', { name: 'Deliver' })).toBeVisible()
        await expect(page.getByRole('main').getByRole('status').filter({ hasText: 'On the way' })).toBeVisible({ timeout: 30_000 })
      })

      await test.step('partner drives towards the customer; the customer sees the partner move', async () => {
        const simulator = partnerApp.page.getByRole('region', { name: 'Location simulator' })
        await simulator.getByRole('button', { name: 'Drive to the customer' }).click()
        await expect(simulator.getByText(/step [3-9] of 20|step 1\d of 20/)).toBeVisible({ timeout: 30_000 })
        const tracking = page.getByRole('region', { name: 'Live tracking' })
        await expect(tracking).toBeVisible()
        // the partner's position reaches delivery-service and the customer's tracking (first name only)
        await until('the customer tracking shows a moving partner', async () => {
          const result = await call('GET', `/api/delivery/orders/${order.orderId}/tracking`, { token: customer.session.token })
          const latitude = Number(result.body?.partnerLocation?.latitude ?? Number.NaN)
          return result.status === 200 && latitude > storeSpot.latitude
        })
        await expect(tracking.getByTitle(`${riderFirstName} (delivery partner)`)).toBeVisible()
        await simulator.getByRole('button', { name: 'Stop driving' }).click()
        await simulator.getByRole('button', { name: 'At the customer' }).click()
      })

      await test.step('partner marks it delivered', async () => {
        const active = partnerApp.page.getByRole('region', { name: 'Active delivery' })
        await active.getByRole('button', { name: 'Delivered' }).click()
        await expect(partnerApp.page.getByText(`Order ${order.orderNumber} delivered`)).toBeVisible()
        await expect(page.getByRole('main').getByRole('status').filter({ hasText: 'Delivered' })).toBeVisible({ timeout: 30_000 })
        await until('the order is DELIVERED', async () => (await orderOf(customer, order.orderId)).status === 'DELIVERED')
        orderId = undefined // nothing to clean up any more
      })
    } finally {
      await partnerApp.context.close()
      await vendorApp.context.close()
    }

    await test.step('customer rates the product, the store and the delivery', async () => {
      const after = page.getByRole('region', { name: 'After delivery' })
      await after.getByRole('link', { name: 'Rate your order' }).click()
      await expect(page.getByRole('heading', { level: 1, name: 'Rate your order' })).toBeVisible()
      await expect(page.getByText('0 of 3 rated')).toBeVisible()

      // The stars are visually hidden radios inside their <label>: choose them like a keyboard/AT user would.
      const choose = async (radio: Locator) => {
        await radio.dispatchEvent('click')
        await expect(radio).toBeChecked()
      }
      const product = page.getByRole('region', { name: productName })
      await choose(product.getByRole('group', { name: `Your rating for ${productName}` }).getByRole('radio', { name: '5 stars — Excellent' }))
      await product.getByRole('textbox', { name: /Title/ }).fill('Fresh and well packed')
      await product.getByRole('textbox', { name: /Your review/ }).fill('Cooked perfectly, will order again.')
      await product.getByRole('button', { name: 'Submit review' }).click()
      await expect(page.getByText('1 of 3 rated')).toBeVisible()

      const storeRegion = page.getByRole('region', { name: store.name })
      await choose(storeRegion.getByRole('radio', { name: '4 stars — Good' }))
      await storeRegion.getByRole('button', { name: 'Submit review' }).click()
      await expect(page.getByText('2 of 3 rated')).toBeVisible()

      // review-service names this target "Delivery partner" (ReviewService.DELIVERY_PARTNER_NAME), not the rider's name
      const delivery = page.getByRole('region', { name: 'The delivery' }).getByRole('region', { name: 'Delivery partner', exact: true })
      await choose(delivery.getByRole('radio', { name: '5 stars — Excellent' }))
      await delivery.getByRole('button', { name: 'Submit review' }).click()
      await expect(page.getByText('You have rated everything in this order. Thank you!')).toBeVisible()
      await expect(page.getByRole('region', { name: productName }).getByRole('img', { name: '5 out of 5 stars' })).toBeVisible()
    })
  })
})
