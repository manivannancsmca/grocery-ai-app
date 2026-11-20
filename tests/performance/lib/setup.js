// Shared setup: one approved store with priced, well-stocked products and N customers with an address next to it,
// all created through the API (same calls as infrastructure/local/scripts/smoke-phase*.sh). Every name carries the
// run tag, so runs never collide and the data is easy to find afterwards.
import { api, claims, clientIp, json, must, PASSWORD, until, ADMIN_EMAIL, ADMIN_PASSWORD } from './http.js'

const WORDS = ['basmati', 'atta', 'toor', 'moong', 'jaggery', 'poha', 'rava', 'chana', 'masoor', 'urad', 'sooji', 'besan']

export function runTag() {
  return (__ENV.RUN_ID || `p${Date.now().toString(36)}`).toLowerCase().replace(/[^a-z0-9]/g, '')
}

function login(email, password, ip) {
  return must('POST', '/api/auth/login', { body: { email, password }, ip, name: 'setup' }).accessToken
}

function register(role, email, fullName, ip) {
  return must('POST', '/api/auth/register', {
    body: { email, password: PASSWORD, fullName, phone: '+919876543210', role },
    ip,
    name: 'setup',
  }).accessToken
}

/**
 * @param {{ customers: number, products: number }} size
 * @returns {{ tag, storeId, products: {id, name, word}[], customers: {email, token, addressId}[], adminToken }}
 */
export function createWorld({ customers, products }) {
  const tag = runTag()
  // a random spot in North Karnataka (away from the smoke tests' stores), the customers ~1 km from the store
  const lat = Math.round((14.2 + Math.random() * 1.6) * 10_000) / 10_000
  const lng = Math.round((74.6 + Math.random() * 1.3) * 10_000) / 10_000
  let ipSeed = 40_000 + Math.floor(Math.random() * 20_000) // distinct "client IPs" for the auth rate limit

  const admin = login(ADMIN_EMAIL, ADMIN_PASSWORD, clientIp(ipSeed++))
  const category = must('POST', '/api/admin/categories', { token: admin, body: { name: `Perf ${tag}` }, name: 'setup' })

  const vendorEmail = `perf-vendor-${tag}@perf.example.com`
  let vendor = register('VENDOR', vendorEmail, `Perf Vendor ${tag}`, clientIp(ipSeed++))
  const vendorId = until('vendor draft', () => json(api('GET', '/api/vendors/me', { token: vendor, name: 'setup', expect: [200, 404] }))?.id)
  must('PUT', '/api/vendors/me', {
    token: vendor,
    name: 'setup',
    body: {
      businessName: `Perf Mart ${tag}`,
      legalName: `Perf Mart ${tag} Pvt Ltd`,
      gstin: '29ABCDE1234F1Z5',
      fssaiLicense: '12345678901234',
      contactEmail: `ops-${tag}@perf.example.com`,
      contactPhone: '+919876543210',
      address: { line1: '1 Market Road', city: 'Hubballi', state: 'Karnataka', postalCode: '580020', country: 'IN', latitude: lat, longitude: lng },
    },
  })
  must('POST', '/api/vendors/me/submit', { token: vendor, name: 'setup' })
  must('POST', `/api/admin/vendors/${vendorId}/approve`, { token: admin, name: 'setup' })
  vendor = until('vendor_id claim', () => {
    const token = login(vendorEmail, PASSWORD, clientIp(ipSeed++))
    return claims(token).vendor_id ? token : null
  }, 30, 1)

  const created = []
  for (let i = 0; i < products; i++) {
    const word = WORDS[i % WORDS.length]
    const name = `Perf ${word} ${tag} ${i}`
    const product = must('POST', '/api/vendor/products', {
      token: vendor,
      name: 'setup',
      body: { name, categoryId: category.id, unitValue: 1, unitOfMeasure: 'KG', foodType: 'VEG' },
    })
    must('POST', `/api/vendor/products/${product.id}/publish`, { token: vendor, name: 'setup' })
    created.push({ id: product.id, name, word })
  }
  for (const product of created) {
    until('pricing + inventory', () =>
      api('GET', `/api/vendor/prices/${product.id}`, { token: vendor, name: 'setup', expect: [200, 404] }).status === 200 &&
      api('GET', `/api/vendor/inventory/${product.id}`, { token: vendor, name: 'setup', expect: [200, 404] }).status === 200,
    )
    must('PUT', `/api/vendor/prices/${product.id}`, { token: vendor, name: 'setup', body: { mrp: 120, sellingPrice: 100 } })
    must('POST', `/api/vendor/inventory/${product.id}/adjustments`, {
      token: vendor,
      name: 'setup',
      body: { type: 'SET', quantity: 100_000, reason: 'STOCK_COUNT' },
    })
  }
  until('products searchable and in stock', () => {
    const page = json(api('GET', `/api/search/products?q=${tag}&inStock=true&size=100`, { name: 'setup' }))
    return page && page.totalElements >= created.length
  }, 60, 1)

  const people = []
  for (let i = 0; i < customers; i++) {
    const email = `perf-c${i}-${tag}@perf.example.com`
    const token = register('CUSTOMER', email, `Perf Shopper ${i}`, clientIp(ipSeed++))
    until('customer profile', () => api('GET', '/api/customers/me', { token, name: 'setup', expect: [200, 404] }).status === 200)
    const address = must('POST', '/api/customers/me/addresses', {
      token,
      name: 'setup',
      body: {
        label: 'Home',
        recipientName: `Perf Shopper ${i}`,
        phone: '+919876543210',
        line1: `${i + 1} Lake View Layout`,
        city: 'Hubballi',
        state: 'Karnataka',
        postalCode: '580020',
        country: 'IN',
        latitude: Math.round((lat + 0.009) * 10_000) / 10_000,
        longitude: Math.round((lng + 0.004) * 10_000) / 10_000,
        isDefault: true,
      },
    })
    people.push({ email, token, addressId: address.id })
  }
  return { tag, storeId: vendorId, products: created, customers: people, adminToken: admin }
}
