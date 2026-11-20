#!/usr/bin/env node
// =====================================================================================
// Local dev: Chennai test data for every stakeholder — grocery stores (vendors), restaurants, customers and delivery
// partners, all APPROVED, plus a few orders in different delivery states. The data lives in
// infrastructure/local/seed/chennai/chennai.json (real Chennai neighbourhoods and coordinates; made-up names).
//
// Everything goes through the API gateway exactly as people do it in the app, so every service stores its own part
// and the usual events run (search index, delivery dispatch, tracking, notifications). No database is touched directly,
// and no approval rule is bypassed: the admin approves each business like in the app.
//
//   1. restaurants: the owner registers, fills in the profile and menu, submits; the admin approves
//   2. vendors:     the owner registers, fills in the business profile, submits; the admin approves; the store adds
//                   its products, prices and opening stock. Products come from the grocery catalog (PRD… codes,
//                   infrastructure/local/seed/catalog/products.csv) and from the Chennai master list of branded
//                   products (CHN… codes, infrastructure/local/seed/chennai/products.csv, with the brand)
//   3. customers:   register and save their delivery addresses (with map coordinates)
//   4. partners:    register, fill in the profile, submit; the admin approves (or reinstates a suspended one)
//   5. orders:      a customer orders from a restaurant or a store and pays (payment simulator); the store accepts;
//                   the chosen partner, online nearby, accepts the offer; then, depending on the scenario, the store
//                   marks it ready, the partner picks it up, rides towards the customer and delivers
//   6. partners' availability at the end: online (with a fresh position) or offline, as in the data file
//   7. every account is checked: it signs in with the right role; businesses and partners are APPROVED; stores have
//      every product priced and in stock, restaurants their whole menu, customers every address with its map location,
//      partners their phone and vehicle (the script fails otherwise)
//
// The "development" block of the data names one account of each role, close together in Velachery
// (the sign-in screen's development accounts dev.customer@grocery.local, dev.vendor@…, dev.restaurant@…, dev.delivery@…,
// password Dev@12345, which identity-service creates), for testing by hand. After seeding,
// verify-workflow.mjs runs a grocery and a restaurant order through those four accounts, step by step.
//
// Prereq: the app is running (infrastructure, services, frontend). Node 18 or newer. The grocery categories come from
//         infrastructure/local/seed/catalog/categories.csv; the ones the stores need and nobody created yet are created
//         here (seed-catalog.mjs is not needed).
// Check:  node infrastructure/local/scripts/seed-chennai.mjs --check
//         checks the data files offline (codes, photos, accounts, orders) and prints which stores and restaurants
//         deliver to every customer address, with the cart's own rules; nothing is sent.
// Run:    node infrastructure/local/scripts/seed-chennai.mjs
// Safe to re-run: accounts, businesses, menu items, products (by SKU = catalog code) and addresses are found again and
// never created twice; an order scenario is skipped when its customer already has an order. A re-run also puts the
// "online" partners back online with a fresh position (positions older than 60 s do not get delivery offers).
// =====================================================================================
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { call, claims, GATEWAY_URL, log, login, must, sleep, waitFor } from './gateway-client.mjs'

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '../../..')
const CHENNAI_DIR = join(REPO_ROOT, 'infrastructure/local/seed/chennai')
const DATA = JSON.parse(readFileSync(join(CHENNAI_DIR, 'chennai.json'), 'utf8'))
const CATALOG_DIR = join(REPO_ROOT, 'infrastructure/local/seed/catalog')
const IMAGE_DIR = join(REPO_ROOT, 'frontend/grocery-web/public/images')
const IMAGE_BASE_URL = process.env.IMAGE_BASE_URL ?? 'http://localhost:6420/images/'
const ADMIN = {
  email: process.env.BOOTSTRAP_ADMIN_EMAIL ?? 'admin@grocery.local',
  password: process.env.BOOTSTRAP_ADMIN_PASSWORD ?? 'Admin@12345',
}
const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']

// ---------------------------------------------------------------------------------------------------------------
// The grocery catalog (product details and photos for the stores)
// ---------------------------------------------------------------------------------------------------------------

/** Parses CSV text into one object per row, keyed by the header. Values with commas are in "double quotes". */
function parseCsv(text) {
  const rows = []
  let row = []
  let value = ''
  let quoted = false
  for (let i = 0; i < text.length; i++) {
    const char = text[i]
    if (quoted) {
      if (char === '"' && text[i + 1] === '"') {
        value += '"'
        i++
      } else if (char === '"') quoted = false
      else value += char
    } else if (char === '"') quoted = true
    else if (char === ',') {
      row.push(value)
      value = ''
    } else if (char === '\n' || char === '\r') {
      if (char === '\r' && text[i + 1] === '\n') i++
      row.push(value)
      rows.push(row)
      row = []
      value = ''
    } else value += char
  }
  if (value || row.length) rows.push([...row, value])
  const [header, ...records] = rows.filter((r) => r.some((cell) => cell.trim() !== ''))
  return records.map((record) => Object.fromEntries(header.map((name, index) => [name.trim(), (record[index] ?? '').trim()])))
}

/** The Chennai master list: branded products (CHN… codes) with a `brand` column, in the catalog's categories. */
const CHENNAI_PRODUCTS = parseCsv(readFileSync(join(CHENNAI_DIR, 'products.csv'), 'utf8'))

const CATALOG = {
  categories: parseCsv(readFileSync(join(CATALOG_DIR, 'categories.csv'), 'utf8')),
  products: new Map(
    [...parseCsv(readFileSync(join(CATALOG_DIR, 'products.csv'), 'utf8')), ...CHENNAI_PRODUCTS].map((product) => [product.code, product]),
  ),
}

/** A store's opening stock of a product: the file's stock × the store's `stockScale`, or 0 when it is in `outOfStock`. */
function openingStock(vendor, product) {
  if (vendor.outOfStock?.includes(product.code)) return 0
  return Math.round(Number(product.stock) * (vendor.stockScale ?? 1))
}

/** The admin's category ids by catalog code (CAT001…); a category the store needs and nobody created yet is created. */
async function categoryIds(admin, codes) {
  const tree = (await must('read categories', 'GET', '/api/admin/categories', admin)).data
  const byName = new Map(tree.map((node) => [node.name.toLowerCase(), node]))
  const ids = new Map()
  for (const code of codes) {
    const category = CATALOG.categories.find((entry) => entry.code === code)
    let node = byName.get(category.name.toLowerCase())
    if (!node) {
      node = (await must(`category ${category.name}`, 'POST', '/api/admin/categories', admin, {
        name: category.name,
        parentId: null,
        description: category.description || null,
        imageUrl: category.image ? IMAGE_BASE_URL + category.image : null,
        sortOrder: Number(category.sort_order) || 0,
      })).data
      log(`  + category ${category.name}`)
    }
    if (!node.active) node = (await must(`activate ${category.name}`, 'POST', `/api/admin/categories/${node.id}/activate`, admin)).data
    ids.set(code, node.id)
  }
  return ids
}

// ---------------------------------------------------------------------------------------------------------------
// Accounts
// ---------------------------------------------------------------------------------------------------------------

/**
 * Each account's password: the data's shared "password", or the entry's own. The development accounts keep the password
 * identity-service gave them (Dev@12345), so the sign-in screen's development buttons keep working.
 */
const PASSWORDS = new Map(
  [
    ...DATA.customers.map((c) => [c.email, c.password]),
    ...DATA.partners.map((p) => [p.email, p.password]),
    ...DATA.vendors.map((v) => [v.owner.email, v.owner.password]),
    ...DATA.restaurants.map((r) => [r.owner.email, r.owner.password]),
  ].filter(([, password]) => password),
)
const passwordOf = (email) => PASSWORDS.get(email) ?? DATA.password

/**
 * Signs in again until the session works with the new rights. An approval revokes the account's older sessions; a
 * sign-in made a moment before that revocation is processed is revoked too, so wait for one that passes `works`.
 */
async function freshSession(email, works) {
  return waitFor(`a fresh session for ${email}`, async () => {
    const token = await login(email, passwordOf(email))
    return { status: 200, token, ok: token !== null && (await works(token)) }
  }, (r) => r.ok, 30).then((r) => r.token)
}

/** Signs in, registering the account first when it does not exist yet. */
async function account(email, role, fullName, phone) {
  const existing = await login(email, passwordOf(email))
  if (existing) return existing
  await must(`register ${email}`, 'POST', '/api/auth/register', null, { email, password: passwordOf(email), fullName, phone, role })
  log(`  + account ${email} (${role})`)
  return login(email, passwordOf(email))
}

// ---------------------------------------------------------------------------------------------------------------
// 1. Restaurants
// ---------------------------------------------------------------------------------------------------------------

/** Creates the menu categories and items that are not there yet (matched by name). */
async function ensureMenu(token, menu) {
  const current = (await must('read the menu', 'GET', '/api/restaurant/menu', token)).data
  for (const section of menu) {
    let category = current.categories.find((entry) => entry.name === section.category)
    if (!category) {
      category = (await must(`menu category ${section.category}`, 'POST', '/api/restaurant/menu/categories', token, { name: section.category })).data
      category.items = []
    }
    for (const item of section.items) {
      if (category.items.some((entry) => entry.name === item.name)) continue
      await must(`menu item ${item.name}`, 'POST', '/api/restaurant/menu/items', token, {
        categoryId: category.id,
        name: item.name,
        description: item.description,
        price: item.price,
        foodType: item.foodType,
      })
    }
  }
}

async function seedRestaurant(admin, restaurant) {
  let token = await account(restaurant.owner.email, 'RESTAURANT_OWNER', restaurant.owner.fullName, restaurant.owner.phone)
  const me = await waitFor('restaurant profile', () => call('GET', '/api/restaurants/me', token), (r) => r.status === 200)
  const id = me.data.id
  if (me.data.status !== 'APPROVED') {
    await must(`profile of ${restaurant.name}`, 'PUT', '/api/restaurants/me', token, {
      name: restaurant.name,
      description: restaurant.description,
      cuisines: restaurant.cuisines,
      fssaiLicense: '12345678901234',
      contactEmail: restaurant.owner.email,
      contactPhone: restaurant.owner.phone,
      address: { ...restaurant.address, country: 'IN' },
      openingHours: DAYS.map((dayOfWeek) => ({ dayOfWeek, ...restaurant.hours })),
    })
    await ensureMenu(token, restaurant.menu)
    if (me.data.status === 'DRAFT' || me.data.status === 'REJECTED') await must(`submit ${restaurant.name}`, 'POST', '/api/restaurants/me/submit', token)
    if (me.data.status === 'SUSPENDED') await must(`reinstate ${restaurant.name}`, 'POST', `/api/admin/restaurants/${id}/reinstate`, admin)
    else await must(`approve ${restaurant.name}`, 'POST', `/api/admin/restaurants/${id}/approve`, admin)
    log(`  + restaurant ${restaurant.name} (${restaurant.address.line2}) approved`)
    await sleep(1000)
    // A new session carries the restaurant_id claim the restaurant's own APIs need.
    token = await freshSession(restaurant.owner.email, async (candidate) =>
      claims(candidate).restaurant_id === id && (await call('GET', '/api/restaurant/menu', candidate)).status === 200,
    )
  } else {
    await ensureMenu(token, restaurant.menu)
  }
  await must('accepting orders', 'PUT', '/api/restaurant/accepting-orders', token, { acceptingOrders: restaurant.acceptingOrders !== false })
  const menu = (await must('read the menu', 'GET', '/api/restaurant/menu', token)).data
  const items = new Map(menu.categories.flatMap((category) => category.items.map((item) => [item.name, item.id])))
  return { ...restaurant, kind: 'restaurant', name: restaurant.name, id, token, items }
}

// ---------------------------------------------------------------------------------------------------------------
// 2. Vendors (grocery stores)
// ---------------------------------------------------------------------------------------------------------------

async function seedVendor(admin, vendor, categories) {
  let token = await account(vendor.owner.email, 'VENDOR', vendor.owner.fullName, vendor.owner.phone)
  const me = await waitFor('vendor profile', () => call('GET', '/api/vendors/me', token), (r) => r.status === 200 && r.data?.id)
  const id = me.data.id
  if (me.data.status !== 'APPROVED') {
    if (me.data.status === 'DRAFT' || me.data.status === 'REJECTED') {
      await must(`profile of ${vendor.businessName}`, 'PUT', '/api/vendors/me', token, {
        businessName: vendor.businessName,
        legalName: vendor.legalName,
        gstin: vendor.gstin,
        fssaiLicense: vendor.fssaiLicense,
        contactEmail: vendor.owner.email,
        contactPhone: vendor.owner.phone,
        address: { ...vendor.address, country: 'IN' },
      })
      await must(`submit ${vendor.businessName}`, 'POST', '/api/vendors/me/submit', token)
    }
    if (me.data.status === 'SUSPENDED') await must(`reinstate ${vendor.businessName}`, 'POST', `/api/admin/vendors/${id}/reinstate`, admin)
    else await must(`approve ${vendor.businessName}`, 'POST', `/api/admin/vendors/${id}/approve`, admin)
    log(`  + store ${vendor.businessName} (${vendor.address.line2}) approved`)
    await sleep(1000)
  }
  // Only an approved store's session carries vendor_id (needed by the store's own APIs).
  token = await freshSession(vendor.owner.email, async (candidate) =>
    claims(candidate).vendor_id === id && (await call('GET', '/api/vendor/products?size=1', candidate)).status === 200,
  )

  // Products from the catalog, found again by SKU (= the catalog code), published. Every page: a product beyond the
  // first page would otherwise be created a second time on a re-run.
  const existing = []
  for (let page = 0, pages = 1; page < pages; page++) {
    const data = (await must('read products', 'GET', `/api/vendor/products?size=100&page=${page}`, token)).data
    existing.push(...data.content)
    pages = data.totalPages ?? 1
  }
  const bySku = new Map(existing.filter((product) => product.sku).map((product) => [product.sku.toUpperCase(), product]))
  const products = vendor.products.map((code) => CATALOG.products.get(code))
  const ids = new Map()
  for (const product of products) {
    let view = bySku.get(product.code.toUpperCase())
    if (!view) {
      view = (await must(`product ${product.name}`, 'POST', '/api/vendor/products', token, {
        name: product.name,
        description: product.description || null,
        categoryId: categories.get(product.category_code),
        brandName: product.brand || null,
        unitValue: Number(product.unit_value),
        unitOfMeasure: product.unit,
        sku: product.code,
        foodType: product.food_type || null,
        imageUrls: [IMAGE_BASE_URL + product.image],
      })).data
    }
    if (view.status !== 'PUBLISHED') view = (await must(`publish ${product.name}`, 'POST', `/api/vendor/products/${view.id}/publish`, token)).data
    ids.set(product.code, view.id)
  }

  // A first price applies at once; an unchanged one is fine on a re-run.
  await waitFor('prices know the products', () => call('GET', '/api/vendor/prices/summary', token), (r) => r.data?.products >= products.length, 60)
  for (const product of products) {
    const result = await call('PUT', `/api/vendor/prices/${ids.get(product.code)}`, token, { mrp: Number(product.mrp), sellingPrice: Number(product.selling_price) })
    if (result.status >= 300 && result.data?.errorCode !== 'PRICE_UNCHANGED') {
      throw new Error(`price of ${product.name}: HTTP ${result.status} ${JSON.stringify(result.data).slice(0, 200)}`)
    }
  }

  // Opening stock only for products with nothing on hand (a re-run never undoes sales). Products the store has in
  // `outOfStock` stay at 0: they are listed but cannot be bought.
  await waitFor('inventory knows the products', () => call('GET', '/api/vendor/inventory/summary', token), (r) => r.data?.products >= products.length, 60)
  // Every page: a store can sell more than one page (100) of products, and a product missing here got no stock.
  const onHand = new Map()
  for (let page = 0, pages = 1; page < pages; page++) {
    const data = (await must('read stock', 'GET', `/api/vendor/inventory?size=100&page=${page}`, token)).data
    data.content.forEach((s) => onHand.set(s.productId, s.onHand))
    pages = data.totalPages ?? 1
  }
  const items = products
    .filter((product) => onHand.get(ids.get(product.code)) === 0 && openingStock(vendor, product) > 0)
    .map((product) => ({ productId: ids.get(product.code), type: 'SET', quantity: openingStock(vendor, product), reason: 'STOCK_COUNT', note: 'Opening stock (seed-chennai)' }))
  // The bulk endpoint takes at most 100 items per call.
  for (let start = 0; start < items.length; start += 100) {
    await must('opening stock', 'POST', '/api/vendor/inventory/adjustments/bulk', token, { items: items.slice(start, start + 100) })
  }

  const itemIds = new Map(products.map((product) => [product.name, ids.get(product.code)]))
  return { ...vendor, kind: 'vendor', name: vendor.businessName, id, token, items: itemIds }
}

// ---------------------------------------------------------------------------------------------------------------
// 3. Customers
// ---------------------------------------------------------------------------------------------------------------

async function seedCustomer(customer) {
  const token = await account(customer.email, 'CUSTOMER', customer.fullName, customer.phone)
  const profile = (await waitFor('customer profile', () => call('GET', '/api/customers/me', token), (r) => r.status === 200)).data
  // An account made elsewhere (the development accounts: name "Dev Customer", no phone) gets the data's name and phone.
  if (profile.fullName !== customer.fullName || profile.phone !== customer.phone) {
    await must(`profile of ${customer.fullName}`, 'PUT', '/api/customers/me', token, { fullName: customer.fullName, phone: customer.phone })
    log(`  + ${customer.email}: name ${customer.fullName}, phone ${customer.phone}`)
  }
  let saved = (await must('read addresses', 'GET', '/api/customers/me/addresses', token)).data
  for (const address of customer.addresses) {
    if (saved.some((entry) => entry.label === address.label)) continue
    await must(`address ${address.label}`, 'POST', '/api/customers/me/addresses', token, {
      ...address,
      recipientName: customer.fullName,
      phone: customer.phone,
      country: 'IN',
    })
    log(`  + ${customer.fullName}: ${address.label}, ${address.line2}`)
  }
  saved = (await must('read addresses', 'GET', '/api/customers/me/addresses', token)).data
  return { ...customer, token, addressIds: new Map(saved.map((entry) => [entry.label, entry.id])) }
}

// ---------------------------------------------------------------------------------------------------------------
// 4. Delivery partners
// ---------------------------------------------------------------------------------------------------------------

async function seedPartner(admin, partner) {
  const token = await account(partner.email, 'DELIVERY_PARTNER', partner.fullName, partner.phone)
  const me = await waitFor('partner profile', () => call('GET', '/api/delivery/partners/me', token), (r) => r.status === 200 && r.data?.id)
  const id = me.data.id
  let status = me.data.status
  if (status === 'DRAFT' || status === 'REJECTED') {
    await must(`profile of ${partner.fullName}`, 'PUT', '/api/delivery/partners/me', token, {
      fullName: partner.fullName,
      phone: partner.phone,
      city: 'Chennai',
      vehicleType: partner.vehicleType,
      vehicleNumber: partner.vehicleNumber,
      drivingLicenseNumber: partner.drivingLicenseNumber,
    })
    await must(`submit ${partner.fullName}`, 'POST', '/api/delivery/partners/me/submit', token)
    status = 'PENDING_APPROVAL'
  }
  if (status === 'PENDING_APPROVAL') {
    await must(`approve ${partner.fullName}`, 'POST', `/api/admin/delivery-partners/${id}/approve`, admin)
    status = 'APPROVED'
  }
  if (status === 'SUSPENDED') {
    await must(`reinstate ${partner.fullName}`, 'POST', `/api/admin/delivery-partners/${id}/reinstate`, admin)
    status = 'APPROVED'
  }
  if (status === me.data.status) return { ...partner, id, token }
  log(`  + partner ${partner.fullName} (${partner.base.area}): ${status}`)
  await sleep(1000)
  // The status change revokes older sessions: continue with one that works for the partner's own APIs.
  const fresh = await freshSession(partner.email, async (candidate) => (await call('GET', '/api/delivery/partners/me/status', candidate)).status === 200)
  return { ...partner, id, token: fresh }
}

/** Online with a fresh position (dispatch only offers deliveries to partners whose position is under 60 s old). */
async function goOnline(partner, at = partner.base) {
  await must(`${partner.fullName} online`, 'PUT', '/api/delivery/partners/me/availability', partner.token, { online: true })
  await report(partner, at)
}

async function report(partner, at) {
  await must(`${partner.fullName} position`, 'PUT', '/api/delivery/partners/me/location', partner.token, {
    latitude: Number(at.latitude.toFixed(6)),
    longitude: Number(at.longitude.toFixed(6)),
  })
}

/** Offline, unless they carry a delivery (the service keeps a partner on a delivery online). */
async function goOffline(partner) {
  await call('PUT', '/api/delivery/partners/me/availability', partner.token, { online: false })
}

/** A point `fraction` (0–1) of the way from `from` to `to` (a straight line; fine at city scale). */
function along(from, to, fraction) {
  return { latitude: from.latitude + (to.latitude - from.latitude) * fraction, longitude: from.longitude + (to.longitude - from.longitude) * fraction }
}

// ---------------------------------------------------------------------------------------------------------------
// 5. Orders
// ---------------------------------------------------------------------------------------------------------------

async function seedOrder(scenario, customers, stores, partners) {
  const customer = customers.get(scenario.customer)
  const store = stores.get(scenario.vendor ?? scenario.restaurant)
  const partner = partners.get(scenario.partner)
  const existing = (await must('read orders', 'GET', '/api/orders?size=1', customer.token)).data
  if (existing.totalElements > 0) {
    log(`  = ${scenario.key}: ${customer.fullName} already has an order (skipped)`)
    return
  }

  // Only this scenario's partner is online, next to the store, so the first offer goes to them.
  for (const other of partners.values()) if (other !== partner) await goOffline(other)
  await goOnline(partner, along(partner.base, store.address, 0.6))

  // The customer orders from the chosen store (one store per cart) and pays with the payment simulator.
  await call('DELETE', '/api/cart', customer.token)
  for (const item of scenario.items) {
    const itemId = store.items.get(item.name)
    if (!itemId) throw new Error(`${store.name} has no "${item.name}"`)
    const line =
      store.kind === 'vendor'
        ? { type: 'PRODUCT', productId: itemId, quantity: item.quantity, replaceCart: true }
        : { type: 'MENU_ITEM', restaurantId: store.id, menuItemId: itemId, quantity: item.quantity, replaceCart: true }
    await must(`add ${item.name}`, 'POST', '/api/cart/items', customer.token, line)
  }
  const addressId = customer.addressIds.get(scenario.address)
  const preview = (await must('checkout preview', 'GET', `/api/cart/preview?addressId=${addressId}`, customer.token)).data
  const total = preview.totals?.total ?? preview.total
  const order = (
    await must('place the order', 'POST', '/api/orders', customer.token, { addressId, expectedTotal: total, note: scenario.note }, { 'Idempotency-Key': `seed-chennai-${scenario.key}` })
  ).data
  await waitFor('payment ready', () => call('GET', `/api/payments/orders/${order.id}/checkout`, customer.token), (r) => r.status === 200)
  const paid = (await must('pay', 'POST', `/api/payments/simulator/orders/${order.id}/pay`, customer.token, { outcome: 'SUCCESS' })).data
  await must('verify the payment', 'POST', '/api/payments/razorpay/verify', customer.token, {
    razorpayOrderId: paid.razorpayOrderId,
    razorpayPaymentId: paid.razorpayPaymentId,
    razorpaySignature: paid.razorpaySignature,
  })

  // The store receives and accepts it: delivery-service starts looking for a partner near the store.
  const orders = `/api/${store.kind}/orders`
  await waitFor('the store receives it', () => call('GET', `${orders}?size=50`, store.token), (r) =>
    (r.data?.content ?? []).some((entry) => entry.orderId === order.id && entry.status === 'NEW'),
  )
  await must('the store accepts', 'POST', `${orders}/${order.id}/accept`, store.token, { readyInMinutes: 15 })

  // The partner gets the offer and accepts it.
  const offers = await waitFor(
    `${partner.fullName} gets the offer`,
    () => call('GET', '/api/delivery/offers', partner.token),
    (r) => Array.isArray(r.data) && r.data.some((offer) => offer.orderNumber === order.orderNumber),
    60,
  )
  const offer = offers.data.find((entry) => entry.orderNumber === order.orderNumber)
  await must('accept the offer', 'POST', `/api/delivery/offers/${offer.offerId}/accept`, partner.token)
  log(`  + ${scenario.key}: order ${order.orderNumber} — ${customer.fullName} ← ${store.name}, partner ${partner.fullName}`)
  if (scenario.target === 'ASSIGNED') return

  // Ready, picked up, then the ride towards the customer (one position every 2.5 s, like a phone would send).
  await must('the store marks it ready', 'POST', `${orders}/${order.id}/ready`, store.token)
  const active = await waitFor('ready for pickup', () => call('GET', '/api/delivery/deliveries/active', partner.token), (r) => r.data?.readyForPickup === true)
  await report(partner, store.address)
  await must('pick up', 'POST', `/api/delivery/deliveries/${active.data.deliveryId}/pickup`, partner.token)
  const home = customer.addresses.find((entry) => entry.label === scenario.address)
  const stops = scenario.target === 'DELIVERED' ? [0.25, 0.5, 0.75, 1] : [0.2, 0.35, 0.5]
  for (const fraction of stops) {
    await sleep(2500)
    await report(partner, along(store.address, home, fraction))
  }
  if (scenario.target === 'DELIVERED') {
    await must('deliver', 'POST', `/api/delivery/deliveries/${active.data.deliveryId}/deliver`, partner.token)
    await waitFor('order delivered', () => call('GET', `/api/orders/${order.id}`, customer.token), (r) => r.data?.status === 'DELIVERED')
  }
  log(`    → ${scenario.target === 'DELIVERED' ? 'delivered' : 'out for delivery, halfway'}`)
}

// ---------------------------------------------------------------------------------------------------------------
// 7. Check every account: it signs in, has its role and is approved, with the details a tester needs
// ---------------------------------------------------------------------------------------------------------------

async function verify(email, role, profilePath, check) {
  const token = await login(email, passwordOf(email))
  if (!token) return { email, role, ok: false, detail: 'cannot sign in' }
  if (!(claims(token).roles ?? []).includes(role)) return { email, role, ok: false, detail: `role is not ${role}` }
  const profile = await call('GET', profilePath, token)
  if (profile.status !== 200) return { email, role, ok: false, detail: `${profilePath}: HTTP ${profile.status}` }
  const problem = await check(profile.data, token)
  return { email, role, ok: problem === null, detail: problem ?? 'OK' }
}

async function verifyAll() {
  const results = []
  // Restaurants: approved, taking orders as the data says, the whole menu there.
  for (const r of DATA.restaurants) {
    results.push(
      await verify(r.owner.email, 'RESTAURANT_OWNER', '/api/restaurants/me', async (data, token) => {
        if (data.status !== 'APPROVED') return `status ${data.status}`
        if (data.acceptingOrders !== (r.acceptingOrders !== false)) return `acceptingOrders is ${data.acceptingOrders}`
        const menu = (await call('GET', '/api/restaurant/menu', token)).data
        const items = new Set((menu?.categories ?? []).flatMap((category) => category.items.map((item) => item.name)))
        const missing = r.menu.flatMap((section) => section.items).filter((item) => !items.has(item.name))
        return missing.length === 0 ? null : `menu items missing: ${missing.map((item) => item.name).join(', ')}`
      }),
    )
  }
  // Stores: approved, every product there, priced, and in stock (except the ones the data lists as out of stock).
  for (const v of DATA.vendors) {
    results.push(
      await verify(v.owner.email, 'VENDOR', '/api/vendors/me', async (data, token) => {
        if (data.status !== 'APPROVED') return `status ${data.status}`
        const products = (await call('GET', '/api/vendor/products?size=1', token)).data
        if (!(products?.totalElements >= v.products.length)) return 'products missing'
        const prices = (await call('GET', '/api/vendor/prices/summary', token)).data
        if (!(prices?.priced >= v.products.length)) return `only ${prices?.priced} of ${v.products.length} products priced`
        const stock = (await call('GET', '/api/vendor/inventory/summary', token)).data
        const stocked = (stock?.inStock ?? 0) + (stock?.lowStock ?? 0)
        const expected = v.products.length - (v.outOfStock?.length ?? 0)
        return stocked >= expected ? null : `only ${stocked} of ${expected} products in stock`
      }),
    )
  }
  // Customers: every address of the data saved, with its map location (checkout and tracking need it).
  for (const c of DATA.customers) {
    results.push(
      await verify(c.email, 'CUSTOMER', '/api/customers/me', async (data, token) => {
        if (data.fullName !== c.fullName || data.phone !== c.phone) return `profile is ${data.fullName}, ${data.phone}`
        const saved = (await call('GET', '/api/customers/me/addresses', token)).data ?? []
        const missing = c.addresses.filter((address) => !saved.some((a) => a.label === address.label && a.latitude !== null && a.longitude !== null))
        return missing.length === 0 ? null : `address without a map location: ${missing.map((address) => address.label).join(', ')}`
      }),
    )
  }
  // Partners: approved, with the phone and the vehicle of the data.
  for (const p of DATA.partners) {
    results.push(
      await verify(p.email, 'DELIVERY_PARTNER', '/api/delivery/partners/me', async (data) => {
        if (data.status !== 'APPROVED') return `status ${data.status}`
        if (data.vehicleType !== p.vehicleType || (data.vehicleNumber ?? null) !== (p.vehicleNumber ?? null)) return `vehicle is ${data.vehicleType} ${data.vehicleNumber}`
        return data.phone ? null : 'no phone number'
      }),
    )
  }
  console.table(results)
  const failed = results.filter((result) => !result.ok)
  if (failed.length > 0) throw new Error(`${failed.length} account(s) failed the check`)
  log(`All ${results.length} accounts sign in, have the right role and are approved.`)
}

// ---------------------------------------------------------------------------------------------------------------
// --check: the data files, offline
// ---------------------------------------------------------------------------------------------------------------

/** cart-service's delivery rules (grocery.cart.delivery in its application.yml). */
const DELIVERY = { roadFactor: 1.3, maxKm: { vendor: 15, restaurant: 12 }, minimumGroceryOrder: 99 }
/** delivery-service's largest search radius around the pickup point (DELIVERY_SEARCH_RADII_KM, default 3,6,10). */
const DISPATCH_RADIUS_KM = 10
/** …and its first one: a partner inside it gets the offer first. */
const FIRST_DISPATCH_RADIUS_KM = 3
/** Greater Chennai, generously: every address and partner area must lie inside. */
const CHENNAI_BOUNDS = { minLat: 12.75, maxLat: 13.3, minLng: 79.95, maxLng: 80.35 }
const UNITS = ['G', 'KG', 'ML', 'L', 'PCS', 'PACK']
const GSTIN = /^33[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/ // 33 = Tamil Nadu
const FSSAI = /^[0-9]{14}$/
const MOBILE = /^\+91[6-9][0-9]{9}$/

/** Straight-line kilometres (haversine), as cart-service computes them. */
function haversineKm(a, b) {
  const rad = (degrees) => (degrees * Math.PI) / 180
  const h =
    Math.sin(rad(b.latitude - a.latitude) / 2) ** 2 +
    Math.cos(rad(a.latitude)) * Math.cos(rad(b.latitude)) * Math.sin(rad(b.longitude - a.longitude) / 2) ** 2
  return 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(h)))
}

/** The distance checkout charges and limits by: the straight line × the road factor, one decimal. */
const roadKm = (a, b) => Math.round(haversineKm(a, b) * DELIVERY.roadFactor * 10) / 10

/**
 * Checks the data files without sending anything: the branded product list, unique accounts, Chennai locations, store
 * products and photos, that every order can be delivered, that a partner works near every store, and that every
 * customer's default address has a store that delivers there. Prints what delivers to each customer address.
 */
function checkData() {
  const problems = []
  const inChennai = (point) =>
    point.latitude >= CHENNAI_BOUNDS.minLat && point.latitude <= CHENNAI_BOUNDS.maxLat && point.longitude >= CHENNAI_BOUNDS.minLng && point.longitude <= CHENNAI_BOUNDS.maxLng

  // The branded master list.
  const categoryCodes = new Set(CATALOG.categories.map((category) => category.code))
  const seen = new Set()
  for (const product of CHENNAI_PRODUCTS) {
    const at = `chennai/products.csv ${product.code} (${product.name})`
    if (!/^CHN\d{5}$/.test(product.code)) problems.push(`${at}: the code must look like CHN00101`)
    if (seen.has(product.code)) problems.push(`${at}: the code is used twice`)
    seen.add(product.code)
    if (!categoryCodes.has(product.category_code)) problems.push(`${at}: unknown category ${product.category_code}`)
    if (!product.brand) problems.push(`${at}: the brand is missing`)
    if (!UNITS.includes(product.unit) || !(Number(product.unit_value) > 0)) problems.push(`${at}: bad pack size`)
    if (!(Number(product.selling_price) >= 1 && Number(product.selling_price) <= Number(product.mrp))) problems.push(`${at}: need 1 <= selling_price <= mrp`)
    if (!Number.isInteger(Number(product.stock)) || Number(product.stock) < 0) problems.push(`${at}: stock must be a whole number`)
    if (!['', 'VEG', 'NON_VEG', 'EGG'].includes(product.food_type)) problems.push(`${at}: food_type must be VEG, NON_VEG, EGG or empty`)
  }

  // One account per e-mail and per mobile number; every place inside Chennai.
  const people = [
    ...DATA.restaurants.map((r) => ({ ...r.owner, place: r.address, what: r.name })),
    ...DATA.vendors.map((v) => ({ ...v.owner, place: v.address, what: v.businessName })),
    ...DATA.customers.flatMap((c) => c.addresses.map((address, i) => ({ email: i === 0 ? c.email : null, phone: i === 0 ? c.phone : null, place: address, what: `${c.fullName} (${address.label})` }))),
    ...DATA.partners.map((p) => ({ email: p.email, phone: p.phone, place: p.base, what: p.fullName })),
  ]
  for (const field of ['email', 'phone']) {
    const values = people.map((person) => person[field]).filter(Boolean)
    for (const value of new Set(values.filter((value, i) => values.indexOf(value) !== i))) problems.push(`${field} ${value} is used by more than one account`)
  }
  for (const person of people) {
    if (person.phone && !MOBILE.test(person.phone)) problems.push(`${person.what}: ${person.phone} is not an Indian mobile number (+91 and 10 digits)`)
    if (!inChennai(person.place)) problems.push(`${person.what}: ${person.place.latitude}, ${person.place.longitude} is outside Chennai`)
  }

  // Stores: registration numbers, products and photos, a partner within the dispatch radius.
  const nearestPartner = (place) => Math.min(...DATA.partners.map((partner) => haversineKm(partner.base, place)))
  for (const vendor of DATA.vendors) {
    const at = `store ${vendor.businessName}`
    if (!GSTIN.test(vendor.gstin)) problems.push(`${at}: GSTIN ${vendor.gstin} is not a Tamil Nadu GSTIN`)
    if (!FSSAI.test(vendor.fssaiLicense)) problems.push(`${at}: the FSSAI licence must have 14 digits`)
    if (new Set(vendor.products).size !== vendor.products.length) problems.push(`${at}: a product is listed twice`)
    for (const code of vendor.products) {
      const product = CATALOG.products.get(code)
      if (!product) problems.push(`${at}: unknown product ${code}`)
      else if (!existsSync(join(IMAGE_DIR, product.image))) problems.push(`${at}: photo ${product.image} of ${product.name} not found`)
    }
    for (const code of vendor.outOfStock ?? []) if (!vendor.products.includes(code)) problems.push(`${at}: outOfStock ${code} is not one of its products`)
    if (nearestPartner(vendor.address) > DISPATCH_RADIUS_KM) problems.push(`${at}: no delivery partner within ${DISPATCH_RADIUS_KM} km`)
  }
  for (const restaurant of DATA.restaurants) {
    if (nearestPartner(restaurant.address) > DISPATCH_RADIUS_KM) problems.push(`restaurant ${restaurant.name}: no delivery partner within ${DISPATCH_RADIUS_KM} km`)
  }

  // Orders: everything they name exists, the store delivers to the address, a grocery order reaches the minimum.
  const stores = new Map([
    ...DATA.vendors.map((v) => [v.key, { kind: 'vendor', name: v.businessName, address: v.address, items: new Map(v.products.filter((code) => !v.outOfStock?.includes(code)).map((code) => CATALOG.products.get(code)).filter(Boolean).map((p) => [p.name, Number(p.selling_price)])) }]),
    ...DATA.restaurants.map((r) => [r.key, { kind: 'restaurant', name: r.name, address: r.address, items: new Map(r.menu.flatMap((section) => section.items.map((entry) => [entry.name, entry.price]))) }]),
  ])
  for (const scenario of DATA.orders) {
    const at = `order ${scenario.key}`
    const customer = DATA.customers.find((c) => c.key === scenario.customer)
    const address = customer?.addresses.find((entry) => entry.label === scenario.address)
    const store = stores.get(scenario.vendor ?? scenario.restaurant)
    if (!customer || !address) problems.push(`${at}: unknown customer ${scenario.customer} or address ${scenario.address}`)
    if (!store) problems.push(`${at}: unknown store ${scenario.vendor ?? scenario.restaurant}`)
    if (!DATA.partners.some((p) => p.key === scenario.partner)) problems.push(`${at}: unknown partner ${scenario.partner}`)
    if (!address || !store) continue
    const missing = scenario.items.filter((entry) => !store.items.has(entry.name))
    if (missing.length > 0) problems.push(`${at}: ${store.name} does not sell ${missing.map((entry) => entry.name).join(', ')} (or it is out of stock)`)
    const km = roadKm(store.address, address)
    if (km > DELIVERY.maxKm[store.kind]) problems.push(`${at}: ${store.name} is ${km} km from the address (limit ${DELIVERY.maxKm[store.kind]} km)`)
    const subtotal = scenario.items.reduce((sum, entry) => sum + (store.items.get(entry.name) ?? 0) * entry.quantity, 0)
    if (store.kind === 'vendor' && subtotal < DELIVERY.minimumGroceryOrder) problems.push(`${at}: ₹${subtotal} is below the ₹${DELIVERY.minimumGroceryOrder} minimum`)
  }

  // The development set: one account of each role, close together, so an order made by hand needs no other account.
  const development = {
    customer: DATA.customers.find((c) => c.key === DATA.development?.customer),
    vendor: DATA.vendors.find((v) => v.key === DATA.development?.vendor),
    restaurant: DATA.restaurants.find((r) => r.key === DATA.development?.restaurant),
    partner: DATA.partners.find((p) => p.key === DATA.development?.partner),
  }
  const missingRoles = Object.entries(development).filter(([, entry]) => !entry).map(([role]) => role)
  if (missingRoles.length > 0) problems.push(`development: ${missingRoles.join(', ')} must name an entry of the lists`)
  else {
    const { customer, vendor, restaurant, partner } = development
    const places = [
      { kind: 'vendor', name: vendor.businessName, address: vendor.address },
      { kind: 'restaurant', name: restaurant.name, address: restaurant.address },
    ]
    for (const place of places) {
      for (const address of customer.addresses) {
        const km = roadKm(place.address, address)
        if (km > DELIVERY.maxKm[place.kind]) problems.push(`development: ${place.name} is ${km} km from ${customer.fullName}'s ${address.label} address (limit ${DELIVERY.maxKm[place.kind]} km)`)
      }
      if (haversineKm(partner.base, place.address) > FIRST_DISPATCH_RADIUS_KM) problems.push(`development: ${partner.fullName} is more than ${FIRST_DISPATCH_RADIUS_KM} km from ${place.name}`)
    }
    if (partner.status !== 'APPROVED') problems.push(`development: ${partner.fullName} must be APPROVED`)
    if (restaurant.acceptingOrders === false) problems.push(`development: ${restaurant.name} must accept orders`)
    if (vendor.outOfStock?.length) problems.push(`development: ${vendor.businessName} should open with every product in stock`)
  }

  // What delivers to every customer address.
  const rows = []
  for (const customer of DATA.customers) {
    for (const address of customer.addresses) {
      const groceries = DATA.vendors
        .map((vendor) => ({ name: vendor.businessName, km: roadKm(vendor.address, address) }))
        .filter((store) => store.km <= DELIVERY.maxKm.vendor)
        .sort((a, b) => a.km - b.km)
      const restaurants = DATA.restaurants.filter((restaurant) => roadKm(restaurant.address, address) <= DELIVERY.maxKm.restaurant)
      if (address.isDefault && groceries.length === 0) problems.push(`${customer.fullName}: no store delivers to the ${address.label} address`)
      rows.push({
        customer: customer.fullName,
        address: `${address.label}, ${address.line2}`,
        'nearest store (road km)': groceries[0] ? `${groceries[0].name} (${groceries[0].km})` : '-',
        'stores that deliver': groceries.length,
        'restaurants that deliver': restaurants.length,
      })
    }
  }
  console.table(rows)
  const products = DATA.vendors.reduce((sum, vendor) => sum + vendor.products.length, 0)
  log(
    `${DATA.vendors.length} stores (${products} store products, ${CHENNAI_PRODUCTS.length} in the branded master list), ${DATA.restaurants.length} restaurants, ` +
      `${DATA.customers.length} customers, ${DATA.partners.length} delivery partners, ${DATA.orders.length} orders.`,
  )
  if (problems.length > 0) throw new Error(`The data has ${problems.length} problem(s):\n  - ${problems.join('\n  - ')}`)
  log('Check passed: nothing was sent.')
}

// ---------------------------------------------------------------------------------------------------------------
// Run
// ---------------------------------------------------------------------------------------------------------------

async function main() {
  if (process.argv.includes('--check')) return checkData()
  log(`Chennai test data → ${GATEWAY_URL}`)
  const admin = await login(ADMIN.email, ADMIN.password)
  if (!admin) throw new Error(`cannot sign in as ${ADMIN.email}`)
  const stores = new Map()

  log('1. Restaurants')
  for (const restaurant of DATA.restaurants) stores.set(restaurant.key, await seedRestaurant(admin, restaurant))

  log('2. Grocery stores (vendors)')
  const codes = new Set(DATA.vendors.flatMap((vendor) => vendor.products.map((code) => CATALOG.products.get(code).category_code)))
  const categories = await categoryIds(admin, [...codes])
  for (const vendor of DATA.vendors) stores.set(vendor.key, await seedVendor(admin, vendor, categories))

  log('3. Customers')
  const customers = new Map()
  for (const customer of DATA.customers) customers.set(customer.key, await seedCustomer(customer))

  log('4. Delivery partners')
  const partners = new Map()
  for (const partner of DATA.partners) partners.set(partner.key, await seedPartner(admin, partner))

  log('   waiting until search lists the restaurants…')
  for (const restaurant of DATA.restaurants) {
    const { latitude, longitude } = restaurant.address
    const id = stores.get(restaurant.key).id
    await waitFor(
      `${restaurant.name} in search`,
      () => call('GET', `/api/search/restaurants?lat=${latitude}&lng=${longitude}&radiusKm=3&q=${encodeURIComponent(restaurant.name)}`),
      (r) => (r.data?.content ?? []).some((card) => card.id === id),
      60,
    )
  }

  log('5. Orders')
  for (const scenario of DATA.orders) await seedOrder(scenario, customers, stores, partners)

  log('6. Partner availability')
  for (const partner of partners.values()) {
    if (partner.finalAvailability === 'ONLINE') await goOnline(partner)
    else await goOffline(partner)
  }

  log('7. Checking every account')
  await verifyAll()
  log(`Every account's password: ${DATA.password}, the development accounts' ${[...new Set(PASSWORDS.values())].join(', ')} (README, "Chennai test accounts").`)
  log('Next: node infrastructure/local/scripts/verify-workflow.mjs runs a whole order through the development accounts.')
}

main().catch((error) => {
  console.error(`\nSeed stopped: ${error.message}`)
  process.exit(1)
})
