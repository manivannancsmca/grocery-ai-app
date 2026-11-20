#!/usr/bin/env node
// =====================================================================================
// Local dev: loads the grocery catalog in infrastructure/local/seed/catalog/ into the running app.
//
//   categories.csv  one row per category: code (CAT001…), name, sort order, photo, description
//   products.csv    one row per product: category code, product code (PRD00101…), name, pack size, MRP,
//                   selling price, opening stock, food type, photo, description
//
// Everything goes through the API gateway, exactly as an admin and a store would do it in the app, so every
// service stores its own part and search is updated by the usual events:
//   1. the admin creates the categories                    → catalog-command-service (category)
//   2. a store vendor is registered, onboarded and approved → vendor-service ("FreshCart Store")
//   3. the store creates and publishes the products        → catalog-command-service (product, product_image).
//      The product code is stored as the product's SKU; that is how a re-run finds the product again.
//   4. the store sets the prices and the opening stock     → pricing-service, inventory-service
//   5. catalog-query-service indexes it all; the script waits until search shows every product.
//
// Photos: frontend/grocery-web/public/images/<image column> (credits in CREDITS.md next to them), served by the
// frontend at /images/. The catalog stores absolute URLs: IMAGE_BASE_URL + image column.
//
// Prereq: the app is running (infrastructure, services and frontend). Node 18 or newer.
// Run:    node infrastructure/local/scripts/seed-catalog.mjs [--dry-run]
//         --dry-run only checks the two files and the photos; nothing is sent.
// Safe to re-run: categories and products are updated to match the files. Opening stock is only set for products
// with nothing on hand, so a re-run never undoes stock that was sold or counted since.
// =====================================================================================
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '../../..')
const DATA_DIR = join(REPO_ROOT, 'infrastructure/local/seed/catalog')
const IMAGE_DIR = join(REPO_ROOT, 'frontend/grocery-web/public/images')

const GATEWAY_URL = process.env.GATEWAY_URL ?? 'http://localhost:8080'
const IMAGE_BASE_URL = process.env.IMAGE_BASE_URL ?? 'http://localhost:6420/images/'
const ADMIN = {
  email: process.env.BOOTSTRAP_ADMIN_EMAIL ?? 'admin@grocery.local',
  password: process.env.BOOTSTRAP_ADMIN_PASSWORD ?? 'Admin@12345',
}
/** The store that sells the catalog. Sign in with it to manage the products, prices and stock. */
const STORE = {
  email: process.env.STORE_EMAIL ?? 'dev.store@grocery.local',
  password: process.env.STORE_PASSWORD ?? 'Dev@12345',
  fullName: 'FreshCart Store',
  profile: {
    businessName: 'FreshCart Store',
    legalName: 'FreshCart Retail Pvt Ltd',
    gstin: '29ABCDE1234F1Z5',
    fssaiLicense: '12345678901234',
    contactEmail: 'dev.store@grocery.local',
    contactPhone: '+919876543210',
    address: {
      line1: '12 MG Road',
      city: 'Bengaluru',
      state: 'Karnataka',
      postalCode: '560001',
      country: 'IN',
      latitude: 12.9716,
      longitude: 77.5946,
    },
  },
}
const UNITS = ['G', 'KG', 'ML', 'L', 'PCS', 'PACK']
const FOOD_TYPES = ['', 'VEG', 'NON_VEG', 'EGG']
/** The APIs return at most 100 rows per page and take at most 100 stock changes per bulk request. */
const PAGE_SIZE = 100

// ---------------------------------------------------------------------------------------------------------------
// 1. Read and check the files
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
        value += '"' // "" inside quotes is one "
        i++
      } else if (char === '"') {
        quoted = false
      } else {
        value += char
      }
    } else if (char === '"') {
      quoted = true
    } else if (char === ',') {
      row.push(value)
      value = ''
    } else if (char === '\n') {
      row.push(value)
      rows.push(row)
      row = []
      value = ''
    } else if (char !== '\r') {
      value += char
    }
  }
  if (value !== '' || row.length > 0) {
    row.push(value)
    rows.push(row)
  }
  const [header, ...data] = rows.filter((cells) => cells.some((cell) => cell.trim() !== ''))
  return data.map((cells) => Object.fromEntries(header.map((name, i) => [name.trim(), (cells[i] ?? '').trim()])))
}

function readCsv(fileName) {
  return parseCsv(readFileSync(join(DATA_DIR, fileName), 'utf8'))
}

/** Reads both files and stops with a list of every problem found (unknown category, missing photo, bad price…). */
function loadCatalog() {
  const categories = readCsv('categories.csv')
  const products = readCsv('products.csv')
  const problems = []
  const categoryCodes = new Set(categories.map((category) => category.code))
  const seen = new Set()

  for (const category of categories) {
    if (!/^CAT\d{3}$/.test(category.code)) problems.push(`category "${category.name}": code must look like CAT001`)
    if (!category.name) problems.push(`category ${category.code}: name is missing`)
    if (category.image && !existsSync(join(IMAGE_DIR, category.image))) problems.push(`category ${category.code}: photo ${category.image} not found`)
  }
  for (const product of products) {
    const where = `product ${product.code} (${product.name})`
    const mrp = Number(product.mrp)
    const sellingPrice = Number(product.selling_price)
    if (seen.has(product.code)) problems.push(`${where}: the code is used twice`)
    seen.add(product.code)
    if (!/^PRD\d{5}$/.test(product.code)) problems.push(`${where}: code must look like PRD00101`)
    if (!categoryCodes.has(product.category_code)) problems.push(`${where}: unknown category ${product.category_code}`)
    if (!UNITS.includes(product.unit)) problems.push(`${where}: unit must be one of ${UNITS.join(', ')}`)
    if (!(Number(product.unit_value) > 0)) problems.push(`${where}: unit_value must be above 0`)
    if (!(sellingPrice >= 1 && sellingPrice <= mrp)) problems.push(`${where}: need 1 <= selling_price <= mrp`)
    if (!Number.isInteger(Number(product.stock)) || Number(product.stock) < 0) problems.push(`${where}: stock must be a whole number`)
    if (!FOOD_TYPES.includes(product.food_type)) problems.push(`${where}: food_type must be VEG, NON_VEG, EGG or empty`)
    if (!existsSync(join(IMAGE_DIR, product.image))) problems.push(`${where}: photo ${product.image} not found`)
  }
  if (problems.length > 0) {
    throw new Error(`The catalog files have ${problems.length} problem(s):\n  - ${problems.join('\n  - ')}`)
  }
  return { categories, products }
}

// ---------------------------------------------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------------------------------------------

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** Sends one request through the gateway and returns { status, data }. Waits and retries when rate-limited (429). */
async function request(method, path, { token, body } = {}) {
  const headers = { Accept: 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  for (let attempt = 1; ; attempt++) {
    const response = await fetch(GATEWAY_URL + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (response.status === 429 && attempt < 10) {
      await sleep(1000)
      continue
    }
    const text = await response.text()
    let data = null
    try {
      data = text ? JSON.parse(text) : null
    } catch {
      data = text
    }
    return { status: response.status, data }
  }
}

/** Like `request`, but returns the body and throws on any error status (with the API's error code and message). */
async function call(method, path, options) {
  const { status, data } = await request(method, path, options)
  if (status >= 400) {
    const reason = [data?.errorCode, data?.detail ?? (typeof data === 'string' ? data : '')].filter(Boolean).join(': ')
    throw new Error(`${method} ${path} failed with ${status} ${reason}`)
  }
  return data
}

/** Reads every page of a paged list. */
async function getAllPages(path, token) {
  const items = []
  for (let page = 0; ; page++) {
    const separator = path.includes('?') ? '&' : '?'
    const result = await call('GET', `${path}${separator}page=${page}&size=${PAGE_SIZE}`, { token })
    items.push(...result.content)
    if (page + 1 >= result.totalPages) return items
  }
}

/** Calls `check` once a second until it returns something other than null/false, or gives up after `seconds`. */
async function waitFor(what, seconds, check) {
  for (let second = 0; second < seconds; second++) {
    const result = await check()
    if (result) return result
    await sleep(1000)
  }
  throw new Error(`Gave up waiting for ${what} after ${seconds} s`)
}

/** Signs in and returns the access token, or null when the email/password is wrong. */
async function login({ email, password }) {
  const { status, data } = await request('POST', '/api/auth/login', { body: { email, password } })
  if (status === 401) return null
  if (status >= 400) throw new Error(`Sign-in as ${email} failed with ${status} ${data?.detail ?? ''}`)
  return data.accessToken
}

/** The claims inside a JWT access token (its middle part). */
function tokenClaims(token) {
  return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'))
}

const imageUrl = (image) => (image ? IMAGE_BASE_URL + image : null)

// ---------------------------------------------------------------------------------------------------------------
// 2. Categories (as the admin)
// ---------------------------------------------------------------------------------------------------------------

/** Creates each category, or updates the top-level category with the same name. Returns category code → id. */
async function seedCategories(adminToken, categories) {
  const tree = await call('GET', '/api/admin/categories', { token: adminToken })
  const existingByName = new Map(tree.map((node) => [node.name.toLowerCase(), node]))
  const idByCode = new Map()

  for (const category of categories) {
    const body = {
      name: category.name,
      parentId: null, // the catalog's categories are top-level
      description: category.description || null,
      imageUrl: imageUrl(category.image),
      sortOrder: Number(category.sort_order) || 0,
    }
    const existing = existingByName.get(category.name.toLowerCase())
    let node = existing
      ? await call('PUT', `/api/admin/categories/${existing.id}`, { token: adminToken, body })
      : await call('POST', '/api/admin/categories', { token: adminToken, body })
    if (!node.active) {
      node = await call('POST', `/api/admin/categories/${node.id}/activate`, { token: adminToken })
    }
    idByCode.set(category.code, node.id)
    console.log(`  ✔ ${category.code} ${category.name} (${existing ? 'updated' : 'created'})`)
  }
  return idByCode
}

// ---------------------------------------------------------------------------------------------------------------
// 3. The store vendor
// ---------------------------------------------------------------------------------------------------------------

/** Registers, onboards and approves the store as needed. Returns a store token that carries the vendor_id claim. */
async function prepareStore(adminToken) {
  let token = await login(STORE)
  if (!token) {
    const { status, data } = await request('POST', '/api/auth/register', {
      body: { email: STORE.email, password: STORE.password, fullName: STORE.fullName, role: 'VENDOR' },
    })
    if (status === 409) throw new Error(`${STORE.email} exists with another password: set STORE_PASSWORD`)
    if (status >= 400) throw new Error(`Registering ${STORE.email} failed with ${status} ${data?.detail ?? ''}`)
    token = data.accessToken
    console.log(`  ✔ registered ${STORE.email}`)
  }

  // vendor-service creates the store's profile from the registration event, usually within a second.
  let vendor = await waitFor('the store profile', 30, async () => {
    const { status, data } = await request('GET', '/api/vendors/me', { token })
    return status === 200 ? data : null
  })
  if (vendor.status === 'DRAFT' || vendor.status === 'REJECTED') {
    await call('PUT', '/api/vendors/me', { token, body: STORE.profile })
    vendor = await call('POST', '/api/vendors/me/submit', { token })
    console.log('  ✔ onboarding submitted')
  }
  if (vendor.status === 'PENDING_APPROVAL') {
    vendor = await call('POST', `/api/admin/vendors/${vendor.id}/approve`, { token: adminToken })
    console.log('  ✔ approved by the admin')
  }
  if (vendor.status !== 'APPROVED') {
    throw new Error(`The store is ${vendor.status}: an admin has to reinstate it before the catalog can be seeded`)
  }

  // Only an approved store's token carries vendor_id. identity-service learns about the approval from an event,
  // so sign in again until the claim is there.
  token = await waitFor('the store token to carry vendor_id', 30, async () => {
    const fresh = await login(STORE)
    return fresh && tokenClaims(fresh).vendor_id === vendor.id ? fresh : null
  })
  console.log(`  ✔ ${vendor.businessName} is approved (${STORE.email} / ${STORE.password})`)
  return { token, vendorId: vendor.id }
}

// ---------------------------------------------------------------------------------------------------------------
// 4. Products, prices and stock (as the store)
// ---------------------------------------------------------------------------------------------------------------

/** Creates or updates every product and publishes it. Returns product code → product id. */
async function seedProducts(store, categories, products, categoryIds) {
  const existing = await getAllPages('/api/vendor/products', store.token) // archived products are left out
  const existingBySku = new Map(existing.filter((p) => p.sku).map((p) => [p.sku.toUpperCase(), p]))
  const idByCode = new Map()

  for (const category of categories) {
    const inCategory = products.filter((product) => product.category_code === category.code)
    let created = 0
    for (const product of inCategory) {
      const body = {
        name: product.name,
        description: product.description || null,
        categoryId: categoryIds.get(product.category_code),
        unitValue: Number(product.unit_value),
        unitOfMeasure: product.unit,
        sku: product.code,
        foodType: product.food_type || null,
        imageUrls: [imageUrl(product.image)],
      }
      const found = existingBySku.get(product.code.toUpperCase())
      let view = found
        ? await call('PUT', `/api/vendor/products/${found.id}`, { token: store.token, body })
        : await call('POST', '/api/vendor/products', { token: store.token, body })
      if (!found) created++
      if (view.status !== 'PUBLISHED') {
        view = await call('POST', `/api/vendor/products/${view.id}/publish`, { token: store.token })
      }
      idByCode.set(product.code, view.id)
    }
    console.log(`  ✔ ${category.code} ${category.name}: ${inCategory.length} products (${created} new)`)
  }
  return idByCode
}

/** Sets each product's MRP and selling price. A first price applies at once. */
async function seedPrices(store, products, productIds) {
  // pricing-service learns about new products from catalog events: wait until it knows all of them.
  await waitFor('pricing-service to know every product', 60, async () => {
    const summary = await call('GET', '/api/vendor/prices/summary', { token: store.token })
    return summary.products >= products.length
  })
  const counts = { applied: 0, unchanged: 0, waitingForApproval: 0 }
  for (const product of products) {
    const { status, data } = await request('PUT', `/api/vendor/prices/${productIds.get(product.code)}`, {
      token: store.token,
      body: { mrp: Number(product.mrp), sellingPrice: Number(product.selling_price) },
    })
    if (status === 200) {
      counts.applied++
    } else if (status === 409 && data?.errorCode === 'PRICE_UNCHANGED') {
      counts.unchanged++
    } else if (status === 202 || (status === 409 && data?.errorCode === 'PRICE_CHANGE_PENDING')) {
      // A change of more than 20% needs an admin (Admin → Pricing); the current price stays live meanwhile.
      counts.waitingForApproval++
      console.log(`  ! ${product.code} ${product.name}: the new price waits for admin approval`)
    } else {
      throw new Error(`Price of ${product.code} failed with ${status} ${data?.errorCode ?? ''} ${data?.detail ?? ''}`)
    }
  }
  console.log(`  ✔ prices: ${counts.applied} set, ${counts.unchanged} already right, ${counts.waitingForApproval} waiting for approval`)
}

/** Gives every product with nothing on hand its opening stock. Products that already have stock are left alone. */
async function seedStock(store, products, productIds) {
  await waitFor('inventory-service to know every product', 60, async () => {
    const summary = await call('GET', '/api/vendor/inventory/summary', { token: store.token })
    return summary.products >= products.length
  })
  const onHandById = new Map((await getAllPages('/api/vendor/inventory', store.token)).map((s) => [s.productId, s.onHand]))
  const items = products
    .map((product) => ({ product, productId: productIds.get(product.code) }))
    .filter(({ product, productId }) => onHandById.get(productId) === 0 && Number(product.stock) > 0)
    .map(({ product, productId }) => ({
      productId,
      type: 'SET',
      quantity: Number(product.stock),
      reason: 'STOCK_COUNT',
      note: 'Opening stock (seed-catalog)',
    }))
  // The bulk API takes up to 100 changes per request.
  for (let start = 0; start < items.length; start += PAGE_SIZE) {
    await call('POST', '/api/vendor/inventory/adjustments/bulk', {
      token: store.token,
      body: { items: items.slice(start, start + PAGE_SIZE) },
    })
  }
  console.log(`  ✔ stock: ${items.length} products stocked, ${products.length - items.length} already had stock`)
}

// ---------------------------------------------------------------------------------------------------------------
// 5. Search
// ---------------------------------------------------------------------------------------------------------------

/** Waits until search shows every product of the store with a price and in stock. */
async function waitForSearch(store, products) {
  // minPrice=0 matches only priced products; inStock=true only products that can be bought.
  const path = `/api/search/products?vendorId=${store.vendorId}&inStock=true&minPrice=0&size=1`
  const result = await waitFor('search to show every product', 90, async () => {
    const page = await call('GET', path)
    return page.totalElements >= products.length ? page : null
  })
  console.log(`  ✔ search shows ${result.totalElements} products from the store, priced and in stock`)
}

/** Prints what a shopper sees for the first products' names and the first category, as a quick check. */
async function showExamples(store, categories, products, categoryIds) {
  for (const product of products.slice(0, 2)) {
    const q = encodeURIComponent(product.name)
    const page = await call('GET', `/api/search/products?q=${q}&vendorId=${store.vendorId}&size=3`)
    const hits = page.content.map((p) => `${p.name} (${p.category?.name}, ₹${p.price?.sellingPrice})`)
    console.log(`  search "${product.name}": ${hits.join(', ') || 'nothing found'}`)
  }
  const [first] = categories
  const page = await call('GET', `/api/search/products?categoryId=${categoryIds.get(first.code)}&size=1`)
  console.log(`  category ${first.name}: ${page.totalElements} products`)
}

// ---------------------------------------------------------------------------------------------------------------

async function main() {
  console.log('== Reading the catalog files')
  const { categories, products } = loadCatalog()
  console.log(`  ✔ ${categories.length} categories and ${products.length} products; every photo is there`)
  if (process.argv.includes('--dry-run')) {
    for (const category of categories) {
      const count = products.filter((product) => product.category_code === category.code).length
      console.log(`    ${category.code} ${category.name}: ${count} products`)
    }
    console.log('Dry run: nothing was sent.')
    return
  }

  const adminToken = await login(ADMIN)
  if (!adminToken) throw new Error(`Admin sign-in failed for ${ADMIN.email}`)

  console.log('== Categories (admin)')
  const categoryIds = await seedCategories(adminToken, categories)

  console.log('== Store')
  const store = await prepareStore(adminToken)

  console.log('== Products')
  const productIds = await seedProducts(store, categories, products, categoryIds)

  console.log('== Prices and stock')
  await seedPrices(store, products, productIds)
  await seedStock(store, products, productIds)

  console.log('== Search')
  await waitForSearch(store, products)
  await showExamples(store, categories, products, categoryIds)
  console.log('Done.')
}

main().catch((error) => {
  console.error(`✘ ${error.message}`)
  process.exit(1)
})
