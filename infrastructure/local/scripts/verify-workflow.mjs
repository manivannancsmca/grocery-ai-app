#!/usr/bin/env node
// =====================================================================================
// Local dev: checks the whole order workflow with the four development accounts of the Chennai data
// ("development" in infrastructure/local/seed/chennai/chennai.json: the sign-in screen's dev.*@grocery.local accounts,
// made a customer, a grocery store, a restaurant and a delivery partner in Velachery by seed-chennai.mjs). Each step
// is done through the API gateway the way that person does it in the app, and is then checked from the other
// people's side too:
//
//   0. the four accounts sign in, with their role, approval and data (addresses, products, menu, vehicle)
//   1. a grocery order:    the customer finds the store near Home, browses its products, fills the cart, checks the
//                          bill, orders and pays (payment simulator) → the store accepts it and packs it → the partner,
//                          online near the store, accepts the offer → the store sees who collects it → the customer sees
//                          the partner → the store marks it ready → the partner picks it up, rides towards the
//                          customer (the customer follows on the map) and delivers it
//   2. a restaurant order: the same with the restaurant, delivered to the Office address
//   3. a rejection:        the store rejects an order (out of stock) → the order is cancelled and the customer refunded
//
// Prereq: the app is running and seed-chennai.mjs has run. Node 18 or newer.
// Run:    node infrastructure/local/scripts/verify-workflow.mjs
// Every run adds three orders to the development accounts' history (their order lists and reports show them) and
// empties the development customer's cart. A step that needs a newer service than the one running is shown as
// "skipped", with what to restart. Anything else that goes wrong stops the run with the API's answer (exit code 1).
// =====================================================================================
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { call, claims, GATEWAY_URL, log, login, must, sleep, waitFor } from './gateway-client.mjs'

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '../../..')
const DATA = JSON.parse(readFileSync(join(REPO_ROOT, 'infrastructure/local/seed/chennai/chennai.json'), 'utf8'))

/** The note on every order this script places: it shows on the store's board and in the partner app. */
const NOTE = 'Test order from verify-workflow.mjs'

/** What the customer buys in each scenario (names from the store's products and the restaurant's menu). */
const GROCERY_ITEMS = [
  { name: 'Aavin Full Cream Milk', quantity: 2 },
  { name: 'India Gate Ponni Boiled Rice', quantity: 1 },
  { name: 'Britannia Good Day Biscuits', quantity: 2 },
]
const RESTAURANT_ITEMS = [
  { name: 'Ghee Roast Dosa', quantity: 2 },
  { name: 'Filter Coffee', quantity: 2 },
]
const REJECTED_ITEMS = [{ name: 'Gold Winner Groundnut Oil', quantity: 1 }]

// ---------------------------------------------------------------------------------------------------------------
// Steps and how they are printed
// ---------------------------------------------------------------------------------------------------------------

/** Thrown by a step that cannot run against the services that are running now (e.g. a service needs a restart). */
class Skip extends Error {}

const summary = { passed: 0, skipped: [], orders: [] }

/** Runs one step: "✓ title — detail", "- title — skipped: why", or "✗ title" and the run stops. */
async function step(title, run) {
  try {
    const detail = await run()
    summary.passed += 1
    log(`  ✓ ${title}${detail ? ` — ${detail}` : ''}`)
  } catch (error) {
    if (error instanceof Skip) {
      summary.skipped.push({ title, why: error.message })
      log(`  - ${title} — skipped: ${error.message}`)
      return
    }
    log(`  ✗ ${title}`)
    throw error
  }
}

const rupees = (amount) => `₹${Number(amount).toFixed(2).replace(/\.00$/, '')}`
const ordinal = (n) => `${n}${n % 10 === 1 && n !== 11 ? 'st' : n % 10 === 2 && n !== 12 ? 'nd' : n % 10 === 3 && n !== 13 ? 'rd' : 'th'}`
const vehicle = (partner) => [partner.vehicleType?.toLowerCase(), partner.vehicleNumber].filter(Boolean).join(' ')
const itemList = (items) => items.map((item) => `${item.quantity} × ${item.name}`).join(', ')

/** A point `fraction` (0–1) of the way from `from` to `to` (a straight line; fine at city scale). */
function along(from, to, fraction) {
  return { latitude: from.latitude + (to.latitude - from.latitude) * fraction, longitude: from.longitude + (to.longitude - from.longitude) * fraction }
}

/** Straight-line kilometres between two points (haversine), one decimal. */
function km(a, b) {
  const rad = (degrees) => (degrees * Math.PI) / 180
  const h =
    Math.sin(rad(b.latitude - a.latitude) / 2) ** 2 +
    Math.cos(rad(a.latitude)) * Math.cos(rad(b.latitude)) * Math.sin(rad(b.longitude - a.longitude) / 2) ** 2
  return Math.round(2 * 6371 * Math.asin(Math.min(1, Math.sqrt(h))) * 10) / 10
}

// ---------------------------------------------------------------------------------------------------------------
// 0. The four development accounts
// ---------------------------------------------------------------------------------------------------------------

const byKey = (list, key) => list.find((entry) => entry.key === key)

/** Signs in with the entry's own password (the development accounts: Dev@12345) or the data's shared one. */
async function signIn(email, role, password = DATA.password) {
  const token = await login(email, password)
  if (!token) throw new Error(`${email} cannot sign in with the password "${password}" (run seed-chennai.mjs first)`)
  const roles = claims(token).roles ?? []
  if (!roles.includes(role)) throw new Error(`${email} has the role(s) ${roles.join(', ')}, not ${role}`)
  return token
}

async function checkAccounts() {
  const dev = DATA.development
  const data = {
    customer: byKey(DATA.customers, dev?.customer),
    vendor: byKey(DATA.vendors, dev?.vendor),
    restaurant: byKey(DATA.restaurants, dev?.restaurant),
    partner: byKey(DATA.partners, dev?.partner),
  }
  if (Object.values(data).some((entry) => !entry)) throw new Error('chennai.json has no complete "development" block')
  const people = {}

  await step(`customer ${data.customer.email} signs in`, async () => {
    const token = await signIn(data.customer.email, 'CUSTOMER', data.customer.password)
    const saved = (await must('read the addresses', 'GET', '/api/customers/me/addresses', token)).data
    const addressIds = new Map()
    for (const address of data.customer.addresses) {
      const entry = saved.find((candidate) => candidate.label === address.label)
      if (!entry || entry.latitude === null || entry.longitude === null) throw new Error(`the ${address.label} address is missing or has no map location`)
      addressIds.set(address.label, entry.id)
    }
    people.customer = { ...data.customer, name: data.customer.fullName, token, addressIds }
    return `${data.customer.fullName}, ${addressIds.size} delivery addresses with map locations (${[...addressIds.keys()].join(', ')})`
  })

  await step(`vendor ${data.vendor.owner.email} signs in`, async () => {
    const token = await signIn(data.vendor.owner.email, 'VENDOR', data.vendor.owner.password)
    const me = (await must('read the store', 'GET', '/api/vendors/me', token)).data
    if (me.status !== 'APPROVED') throw new Error(`${me.businessName} is ${me.status}, not APPROVED`)
    if (claims(token).vendor_id !== me.id) throw new Error('the session does not carry the store (vendor_id)')
    const prices = (await must('read the prices', 'GET', '/api/vendor/prices/summary', token)).data
    const stock = (await must('read the stock', 'GET', '/api/vendor/inventory/summary', token)).data
    if (prices.priced < data.vendor.products.length) throw new Error(`only ${prices.priced} of ${data.vendor.products.length} products have a price`)
    if (stock.outOfStock > 0) throw new Error(`${stock.outOfStock} product(s) are out of stock`)
    people.vendor = { ...data.vendor, kind: 'vendor', name: me.businessName, id: me.id, token }
    return `${me.businessName} (owner ${data.vendor.owner.fullName}) APPROVED; ${prices.products} products, ${prices.priced} priced, ${stock.inStock + stock.lowStock} in stock`
  })

  await step(`restaurant owner ${data.restaurant.owner.email} signs in`, async () => {
    const token = await signIn(data.restaurant.owner.email, 'RESTAURANT_OWNER', data.restaurant.owner.password)
    const me = (await must('read the restaurant', 'GET', '/api/restaurants/me', token)).data
    if (me.status !== 'APPROVED') throw new Error(`${me.name} is ${me.status}, not APPROVED`)
    if (!me.acceptingOrders) throw new Error(`${me.name} is not accepting orders`)
    if (claims(token).restaurant_id !== me.id) throw new Error('the session does not carry the restaurant (restaurant_id)')
    const menu = (await must('read the menu', 'GET', '/api/restaurant/menu', token)).data
    const dishes = menu.categories.reduce((sum, category) => sum + category.items.length, 0)
    people.restaurant = { ...data.restaurant, kind: 'restaurant', name: me.name, id: me.id, token }
    return `${me.name} (owner ${data.restaurant.owner.fullName}) APPROVED, accepting orders; ${dishes} dishes in ${menu.categories.length} menu sections`
  })

  await step(`delivery partner ${data.partner.email} signs in`, async () => {
    const token = await signIn(data.partner.email, 'DELIVERY_PARTNER', data.partner.password)
    const me = (await must('read the partner profile', 'GET', '/api/delivery/partners/me', token)).data
    if (me.status !== 'APPROVED') throw new Error(`${me.fullName} is ${me.status}, not APPROVED`)
    if (!me.vehicleType || !me.phone) throw new Error('the profile has no vehicle or phone')
    people.partner = { ...data.partner, name: me.fullName, token }
    return `${me.fullName} APPROVED; ${vehicle(me)}, ${me.phone}, based in ${data.partner.base.area}`
  })

  await finishLeftover(people)
  return people
}

/**
 * The partner can carry one delivery at a time. A delivery of this script left over by an earlier run that stopped
 * half-way is finished first; any other delivery is the tester's, so the run stops and says so.
 */
async function finishLeftover(people) {
  const { partner } = people
  const active = await call('GET', '/api/delivery/deliveries/active', partner.token)
  if (active.status === 204) return
  const delivery = active.data
  if (delivery?.note !== NOTE) {
    throw new Error(`${partner.name} is still on delivery ${delivery?.orderNumber} (${delivery?.status}). Finish it in the partner app, then run again.`)
  }
  await step(`finish delivery ${delivery.orderNumber} left over by an earlier run`, async () => {
    const store = delivery.orderType === 'GROCERY' ? people.vendor : people.restaurant
    if (!delivery.readyForPickup) await call('POST', `/api/${store.kind}/orders/${delivery.orderId}/ready`, store.token)
    if (delivery.status === 'ASSIGNED') {
      await waitFor('ready for pickup', () => call('GET', '/api/delivery/deliveries/active', partner.token), (r) => r.data?.readyForPickup === true)
      await must('pick up', 'POST', `/api/delivery/deliveries/${delivery.deliveryId}/pickup`, partner.token)
    }
    await must('deliver', 'POST', `/api/delivery/deliveries/${delivery.deliveryId}/deliver`, partner.token)
    return 'delivered'
  })
}

// ---------------------------------------------------------------------------------------------------------------
// The partner's availability and position
// ---------------------------------------------------------------------------------------------------------------

/** delivery-service drops a position sent less than 2 s after the last one it stored (a phone sends one every few s). */
const LOCATION_MIN_INTERVAL_MS = 2100

async function report(partner, at) {
  const wait = (partner.reportedAt ?? 0) + LOCATION_MIN_INTERVAL_MS - Date.now()
  if (wait > 0) await sleep(wait)
  await must(`${partner.name}'s position`, 'PUT', '/api/delivery/partners/me/location', partner.token, {
    latitude: Number(at.latitude.toFixed(6)),
    longitude: Number(at.longitude.toFixed(6)),
  })
  partner.reportedAt = Date.now()
}

/** Online with a fresh position: dispatch only offers deliveries to partners whose position is under 60 s old. */
async function goOnline(partner, at) {
  await must(`${partner.name} online`, 'PUT', '/api/delivery/partners/me/availability', partner.token, { online: true })
  await report(partner, at)
}

// ---------------------------------------------------------------------------------------------------------------
// The customer: find the store, fill the cart, order and pay
// ---------------------------------------------------------------------------------------------------------------

/** Waits until the customer's order has `status` (events take a moment to reach order-service) and returns it. */
async function orderReaches(customer, order, status, seconds = 30) {
  const result = await waitFor(`order ${order.orderNumber} ${status}`, () => call('GET', `/api/orders/${order.id}`, customer.token), (r) => r.data?.status === status, seconds)
  return result.data
}

async function findStore(customer, store, addressLabel) {
  const address = customer.addresses.find((entry) => entry.label === addressLabel)
  if (store.kind === 'vendor') {
    await step(`${customer.name} finds ${store.name} among the grocery stores near ${addressLabel}`, async () => {
      const nearby = await call('GET', `/api/stores/nearby?lat=${address.latitude}&lng=${address.longitude}`, customer.token)
      if (nearby.status === 401 || nearby.status === 404) throw new Skip('the store directory is not deployed yet (restart vendor-service and api-gateway)')
      if (nearby.status !== 200) throw new Error(`stores near you: HTTP ${nearby.status} ${JSON.stringify(nearby.data).slice(0, 200)}`)
      const index = nearby.data.content.findIndex((card) => card.id === store.id)
      if (index < 0) throw new Error(`${store.name} is not among the ${nearby.data.content.length} stores listed`)
      const card = nearby.data.content[index]
      if (!card.deliversHere) throw new Error(`${store.name} is listed but does not deliver there (${card.distanceKm} km)`)
      return `${ordinal(index + 1)} of ${nearby.data.content.length} stores that deliver, ${card.distanceKm} km by road`
    })
  } else {
    await step(`${customer.name} finds ${store.name} among the restaurants near ${addressLabel}`, async () => {
      const query = `lat=${address.latitude}&lng=${address.longitude}&radiusKm=10&q=${encodeURIComponent(store.name)}`
      const found = await waitFor('restaurant search', () => call('GET', `/api/search/restaurants?${query}`, customer.token), (r) =>
        (r.data?.content ?? []).some((card) => card.id === store.id),
      )
      const card = found.data.content.find((entry) => entry.id === store.id)
      return `${card.distanceKm ?? km(store.address, address)} km away`
    })
  }
}

/** The cart lines for `items`: the customer reads the store's products (search) or the restaurant's menu. */
async function chooseItems(customer, store, items) {
  let lines
  if (store.kind === 'vendor') {
    await step(`${customer.name} browses ${store.name} and picks ${itemList(items)}`, async () => {
      // Each item searched by name in the store, as a customer does: a big store's first page need not hold them.
      const found = await waitFor(
        `${store.name}'s products in search`,
        async () => {
          const content = []
          for (const item of items) {
            const page = await call('GET', `/api/search/products?vendorId=${store.id}&q=${encodeURIComponent(item.name)}&size=10`, customer.token)
            content.push(...(page.data?.content ?? []))
          }
          const all = await call('GET', `/api/search/products?vendorId=${store.id}&size=1`, customer.token)
          return { status: 200, data: { content, totalElements: all.data?.totalElements } }
        },
        (r) => items.every((item) => r.data.content.some((card) => card.name === item.name)),
        60,
      )
      lines = items.map((item) => {
        const card = found.data.content.find((entry) => entry.name === item.name)
        if (!card.inStock) throw new Error(`${item.name} is out of stock at ${store.name}`)
        return { ...item, line: { type: 'PRODUCT', productId: card.id, quantity: item.quantity } }
      })
      return `${found.data.totalElements} products in the store`
    })
  } else {
    await step(`${customer.name} opens the menu and picks ${itemList(items)}`, async () => {
      const page = (await must('the restaurant page', 'GET', `/api/catalog/restaurants/${store.id}`, customer.token)).data
      const dishes = page.menu.flatMap((category) => category.items)
      lines = items.map((item) => {
        const dish = dishes.find((entry) => entry.name === item.name)
        if (!dish?.available) throw new Error(`${item.name} is not on the menu or not available`)
        return { ...item, line: { type: 'MENU_ITEM', restaurantId: store.id, menuItemId: dish.id, quantity: item.quantity } }
      })
      return `${dishes.length} dishes on the menu`
    })
  }
  return lines
}

/** Fills the cart, checks the bill for the address, places the order and pays with the payment simulator. */
async function placeAndPay(customer, store, lines, addressLabel) {
  const addressId = customer.addressIds.get(addressLabel)
  let preview
  await step(`${customer.name} adds them to the cart and checks the bill for ${addressLabel}`, async () => {
    await call('DELETE', '/api/cart', customer.token)
    for (const line of lines) await must(`add ${line.name}`, 'POST', '/api/cart/items', customer.token, { ...line.line, replaceCart: true })
    preview = (await must('checkout preview', 'GET', `/api/cart/preview?addressId=${addressId}`, customer.token)).data
    if (!preview.canCheckout) throw new Error(`checkout is blocked: ${JSON.stringify(preview.issues)}`)
    return `items ${rupees(preview.subtotal)} + delivery ${rupees(preview.delivery.fee)} (${preview.delivery.distanceKm} km) = ${rupees(preview.total)}`
  })

  let order
  await step(`${customer.name} places the order and pays`, async () => {
    order = (
      await must('place the order', 'POST', '/api/orders', customer.token, { addressId, expectedTotal: preview.total, note: NOTE }, {
        'Idempotency-Key': `verify-workflow-${store.kind}-${Date.now()}`,
      })
    ).data
    await waitFor('the payment is ready', () => call('GET', `/api/payments/orders/${order.id}/checkout`, customer.token), (r) => r.status === 200)
    const paid = (await must('pay', 'POST', `/api/payments/simulator/orders/${order.id}/pay`, customer.token, { outcome: 'SUCCESS' })).data
    await must('verify the payment', 'POST', '/api/payments/razorpay/verify', customer.token, {
      razorpayOrderId: paid.razorpayOrderId,
      razorpayPaymentId: paid.razorpayPaymentId,
      razorpaySignature: paid.razorpaySignature,
    })
    const confirmed = await orderReaches(customer, order, 'CONFIRMED')
    return `order ${order.orderNumber}: ${rupees(confirmed.total)} paid (${confirmed.payment?.status}), the order is ${confirmed.status}`
  })
  return order
}

// ---------------------------------------------------------------------------------------------------------------
// 1 and 2. An order from placing to delivery
// ---------------------------------------------------------------------------------------------------------------

async function deliveredOrder(people, store, addressLabel, items) {
  const { customer, partner } = people
  const orders = `/api/${store.kind}/orders`
  const address = customer.addresses.find((entry) => entry.label === addressLabel)
  const preparing = store.kind === 'vendor' ? 'packing' : 'preparing'

  await findStore(customer, store, addressLabel)
  const lines = await chooseItems(customer, store, items)
  const order = await placeAndPay(customer, store, lines, addressLabel)

  await step(`${partner.name} goes online near ${store.name}`, async () => {
    const at = along(partner.base, store.address, 0.7)
    await goOnline(partner, at)
    return `${km(at, store.address)} km from the store`
  })

  await step(`${store.name} gets the order on its board and accepts it`, async () => {
    await waitFor(`${store.name} receives it`, () => call('GET', `${orders}?status=NEW&size=50`, store.token), (r) =>
      (r.data?.content ?? []).some((entry) => entry.orderId === order.id),
    )
    const accepted = (await must('accept', 'POST', `${orders}/${order.id}/accept`, store.token, { readyInMinutes: store.kind === 'vendor' ? 15 : 20 })).data
    await orderReaches(customer, order, 'ACCEPTED')
    return `ready in ${accepted.readyInMinutes} min; the customer sees it accepted`
  })

  await step(`${store.name} starts ${preparing} it`, async () => {
    const view = (await must(preparing, 'POST', `${orders}/${order.id}/preparing`, store.token)).data
    await orderReaches(customer, order, 'PREPARING')
    return `the store's order is ${view.status}; the customer sees it too`
  })

  let deliveryId
  await step(`${partner.name} gets the delivery offer and accepts it`, async () => {
    let reportedAt = Date.now()
    const offers = await waitFor(
      `the offer for ${order.orderNumber} (is another partner online nearby, or delivery-service down?)`,
      async () => {
        if (Date.now() - reportedAt > 20_000) {
          await report(partner, along(partner.base, store.address, 0.7))
          reportedAt = Date.now()
        }
        return call('GET', '/api/delivery/offers', partner.token)
      },
      (r) => Array.isArray(r.data) && r.data.some((offer) => offer.orderNumber === order.orderNumber),
      90,
    )
    const offer = offers.data.find((entry) => entry.orderNumber === order.orderNumber)
    await must('accept the offer', 'POST', `/api/delivery/offers/${offer.offerId}/accept`, partner.token)
    deliveryId = offer.deliveryId
    return `pickup ${offer.distanceKm} km away, trip ${offer.tripKm} km`
  })

  await step(`${store.name} sees who collects the order`, async () => {
    const result = await call('GET', '/api/delivery/store/deliveries', store.token)
    if (result.status === 404 || result.status === 405) throw new Skip('the store delivery view is not deployed yet (restart delivery-service)')
    if (result.status !== 200) throw new Error(`store deliveries: HTTP ${result.status} ${JSON.stringify(result.data).slice(0, 200)}`)
    const entry = result.data.find((delivery) => delivery.orderId === order.id)
    if (!entry?.partner) throw new Error('the order shows no delivery partner')
    if (entry.partner.name !== partner.name) throw new Error(`the order shows ${entry.partner.name}, not ${partner.name}`)
    const away =
      entry.distanceToStoreKm === null ? '' : entry.distanceToStoreKm <= 0.1 ? ', at the store' : `, ${entry.distanceToStoreKm} km away (about ${entry.etaToStoreMinutes} min)`
    return `${entry.partner.name}, ${entry.partner.phone}, ${vehicle(entry.partner)}${away}`
  })

  await step(`${customer.name} sees the partner assigned`, async () => {
    const tracking = (await waitFor('tracking', () => call('GET', `/api/delivery/orders/${order.id}/tracking`, customer.token), (r) => r.data?.status === 'ASSIGNED')).data
    return `${tracking.partner?.name} on a ${tracking.partner?.vehicleType?.toLowerCase()}, arriving in about ${tracking.etaMinutes} min`
  })

  await step(`${store.name} marks it ready for pickup`, async () => {
    await must('ready', 'POST', `${orders}/${order.id}/ready`, store.token)
    await waitFor('the partner app shows it ready', () => call('GET', '/api/delivery/deliveries/active', partner.token), (r) => r.data?.readyForPickup === true)
    await orderReaches(customer, order, 'READY_FOR_PICKUP')
    return 'the partner app and the customer see it ready'
  })

  await step(`${partner.name} goes to the store and picks the order up`, async () => {
    const active = (await must('the active delivery', 'GET', '/api/delivery/deliveries/active', partner.token)).data
    if (active.pickup?.latitude == null || active.drop?.latitude == null) throw new Error('the delivery has no map location for the store or the customer (no directions)')
    await report(partner, store.address)
    await must('pick up', 'POST', `/api/delivery/deliveries/${deliveryId}/pickup`, partner.token)
    await orderReaches(customer, order, 'OUT_FOR_DELIVERY')
    await waitFor('the store sees it picked up', () => call('GET', `${orders}/${order.id}`, store.token), (r) => r.data?.status === 'PICKED_UP')
    return `directions to ${active.pickup.name}, then to ${active.drop.recipientName}; the store shows it picked up, the customer out for delivery`
  })

  await step(`${partner.name} rides to ${customer.name}'s ${addressLabel}; ${customer.name} follows on the map`, async () => {
    const etas = []
    for (const fraction of [0.25, 0.5, 0.75]) {
      await sleep(500) // with report()'s 2.1 s spacing: a position every ~2.5 s, like a phone
      const at = along(store.address, address, fraction)
      await report(partner, at)
      const tracking = await waitFor(
        'the new position on the customer’s map',
        () => call('GET', `/api/delivery/orders/${order.id}/tracking`, customer.token),
        (r) => Math.abs((r.data?.partnerLocation?.latitude ?? 0) - at.latitude) < 1e-4 && Math.abs((r.data?.partnerLocation?.longitude ?? 0) - at.longitude) < 1e-4,
        15,
      )
      etas.push(tracking.data.etaMinutes)
    }
    return `3 positions on the map, arriving in ${etas.join(' → ')} min`
  })

  await step(`${partner.name} delivers it`, async () => {
    await report(partner, address)
    await must('deliver', 'POST', `/api/delivery/deliveries/${deliveryId}/deliver`, partner.token)
    const delivered = await orderReaches(customer, order, 'DELIVERED')
    const active = await call('GET', '/api/delivery/deliveries/active', partner.token)
    if (active.status !== 204) throw new Error(`${partner.name} still has an active delivery`)
    return `order ${delivered.orderNumber} is DELIVERED; ${partner.name} is free for the next one`
  })

  summary.orders.push(`${order.orderNumber} (${store.name} → ${addressLabel}, delivered)`)
}

// ---------------------------------------------------------------------------------------------------------------
// 3. The store rejects an order
// ---------------------------------------------------------------------------------------------------------------

async function rejectedOrder(people, store, addressLabel, items) {
  const { customer } = people
  const lines = await chooseItems(customer, store, items)
  const order = await placeAndPay(customer, store, lines, addressLabel)

  await step(`${store.name} rejects it: out of stock`, async () => {
    await waitFor(`${store.name} receives it`, () => call('GET', `/api/${store.kind}/orders?status=NEW&size=50`, store.token), (r) =>
      (r.data?.content ?? []).some((entry) => entry.orderId === order.id),
    )
    const view = (await must('reject', 'POST', `/api/${store.kind}/orders/${order.id}/reject`, store.token, { reason: 'OUT_OF_STOCK' })).data
    return `the store's order is ${view.status}`
  })

  // order-service cancels an order the store rejected (reason STORE_REJECTED, by STORE) and refunds the payment.
  await step(`${customer.name} sees it cancelled by the store and refunded`, async () => {
    const result = await waitFor(
      `order ${order.orderNumber} cancelled and refunded`,
      () => call('GET', `/api/orders/${order.id}`, customer.token),
      (r) => r.data?.status === 'CANCELLED' && r.data?.cancellation?.reason === 'STORE_REJECTED' && r.data?.payment?.status === 'REFUNDED',
      45,
    )
    const view = result.data
    return `order ${view.status} (${view.cancellation.reason}: ${view.cancellation.note}), ${rupees(view.payment.refundedAmount)} ${view.payment.status}`
  })

  summary.orders.push(`${order.orderNumber} (${store.name} → ${addressLabel}, rejected by the store, refunded)`)
}

// ---------------------------------------------------------------------------------------------------------------
// Run
// ---------------------------------------------------------------------------------------------------------------

async function main() {
  log(`Workflow check with the development accounts → ${GATEWAY_URL}\n`)
  log('0. The accounts')
  const people = await checkAccounts()

  log(`\n1. A grocery order: ${people.customer.name} → ${people.vendor.name} → ${people.partner.name}`)
  await deliveredOrder(people, people.vendor, 'Home', GROCERY_ITEMS)

  log(`\n2. A restaurant order: ${people.customer.name} → ${people.restaurant.name} → ${people.partner.name}`)
  await deliveredOrder(people, people.restaurant, 'Office', RESTAURANT_ITEMS)

  log(`\n3. A rejected order: ${people.customer.name} → ${people.vendor.name}`)
  await rejectedOrder(people, people.vendor, 'Parents', REJECTED_ITEMS)

  // The partner ends online at their base, like after seeding (the position goes stale after 60 s: no offers).
  await goOnline(people.partner, people.partner.base)

  log(`\nWorkflow check passed: ${summary.passed} steps passed${summary.skipped.length ? `, ${summary.skipped.length} skipped` : ''}.`)
  for (const skipped of summary.skipped) log(`  skipped: ${skipped.title} (${skipped.why})`)
  log(`Orders made: ${summary.orders.join('; ')}.`)
  log(`Sign in as any development account (the sign-in screen lists them) to see them in the app.`)
}

main().catch((error) => {
  console.error(`\nWorkflow check stopped: ${error.message}`)
  process.exit(1)
})
