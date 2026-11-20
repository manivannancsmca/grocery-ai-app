/**
 * Test data built through the API: approved stores with stocked products, customers with an address, approved
 * delivery partners, paid orders. Every name, e-mail and code carries a unique tag, and every journey gets its own
 * random spot on the map (North Karnataka, away from the smoke tests' Bengaluru and Mysuru stores), so delivery
 * offers only reach this journey's partner and reruns never collide.
 */
import {
  ADMIN_EMAIL,
  ADMIN_PASSWORD,
  call,
  claims,
  type Json,
  login,
  ok,
  register,
  reloginUntilClaim,
  RUN_ID,
  type Session,
  until,
} from './api'

export interface GeoPoint {
  latitude: number
  longitude: number
}

const round4 = (value: number) => Math.round(value * 10_000) / 10_000

/** A short, lower-case, search-friendly tag unique to this test: `<run id tail><3 random chars>`. */
export function uniqueTag(): string {
  return `${RUN_ID.slice(-5)}${Math.random().toString(36).slice(2, 5)}`.replace(/[^a-z0-9]/g, 'x')
}

export function testEmail(prefix: string, tag: string): string {
  return `${prefix}-${tag}@e2e.example.com`
}

/** A random point in a 1.6° × 1.3° box around Hubballi–Dharwad–Belagavi. */
export function randomSpot(): GeoPoint {
  return { latitude: round4(14.2 + Math.random() * 1.6), longitude: round4(74.6 + Math.random() * 1.3) }
}

/** `point` moved by roughly `northKm` / `eastKm` (fine at these latitudes). */
export function offset(point: GeoPoint, northKm: number, eastKm: number): GeoPoint {
  return { latitude: round4(point.latitude + northKm / 111), longitude: round4(point.longitude + eastKm / 107.5) }
}

let adminSession: Session | null = null

/** The bootstrap SUPER_ADMIN (cached per worker; logs in again when the token is about to expire). */
export async function admin(): Promise<Session> {
  const fresh = adminSession && Number(claims(adminSession.token).exp) * 1000 - Date.now() > 120_000
  if (!fresh) adminSession = await login(ADMIN_EMAIL, ADMIN_PASSWORD)
  return adminSession as Session
}

/** A new admin user with one admin role (SUPER_ADMIN creates it). */
export async function createAdminUser(role: string, tag: string): Promise<Session> {
  const email = testEmail(role.toLowerCase().replace(/_/g, '-'), tag)
  const password = 'Admin1234'
  await ok('POST', '/api/admin/users', {
    token: (await admin()).token,
    body: { email, fullName: `E2E ${role} ${tag}`, password, roles: [role] },
  })
  return login(email, password)
}

export async function createCategory(name: string): Promise<string> {
  const category = await ok('POST', '/api/admin/categories', { token: (await admin()).token, body: { name } })
  return String(category.id)
}

export interface Store {
  session: Session
  vendorId: string
  name: string
  location: GeoPoint
}

/** A vendor that registered, filled in its business profile and submitted it: waiting for an admin. */
export async function createPendingVendor(tag: string, location: GeoPoint, name = `E2E Mart ${tag}`): Promise<Store> {
  const session = await register('VENDOR', testEmail('vendor', tag), `Vendor ${tag}`)
  const vendorId = await until('the vendor draft', async () => {
    const result = await call('GET', '/api/vendors/me', { token: session.token })
    return result.status === 200 ? String(result.body.id) : null
  })
  await ok('PUT', '/api/vendors/me', {
    token: session.token,
    body: {
      businessName: name,
      legalName: `${name} Pvt Ltd`,
      gstin: '29ABCDE1234F1Z5',
      fssaiLicense: '12345678901234',
      contactEmail: `ops-${tag}@e2e.example.com`,
      contactPhone: '+919876543210',
      address: {
        line1: '4 Station Road',
        city: 'Hubballi',
        state: 'Karnataka',
        postalCode: '580020',
        country: 'IN',
        latitude: location.latitude,
        longitude: location.longitude,
      },
    },
  })
  await ok('POST', '/api/vendors/me/submit', { token: session.token })
  return { session, vendorId, name, location }
}

/** An approved vendor whose access token already carries `vendor_id`. */
export async function createApprovedVendor(tag: string, location: GeoPoint, name?: string): Promise<Store> {
  const store = await createPendingVendor(tag, location, name)
  await ok('POST', `/api/admin/vendors/${store.vendorId}/approve`, { token: (await admin()).token })
  store.session = await reloginUntilClaim(store.session, 'vendor_id')
  return store
}

export interface ProductSpec {
  categoryId: string
  name: string
  sellingPrice: number
  mrp: number
  stock: number
}

/** A published, priced and stocked product that search already finds. */
export async function createProduct(store: Store, spec: ProductSpec): Promise<string> {
  const token = store.session.token
  const product = await ok('POST', '/api/vendor/products', {
    token,
    body: { name: spec.name, categoryId: spec.categoryId, unitValue: 1, unitOfMeasure: 'KG', foodType: 'VEG' },
  })
  const productId = String(product.id)
  await ok('POST', `/api/vendor/products/${productId}/publish`, { token })
  await until('pricing and inventory know the product', async () => {
    const [price, stock] = await Promise.all([
      call('GET', `/api/vendor/prices/${productId}`, { token }),
      call('GET', `/api/vendor/inventory/${productId}`, { token }),
    ])
    return price.status === 200 && stock.status === 200
  })
  await ok('PUT', `/api/vendor/prices/${productId}`, { token, body: { mrp: spec.mrp, sellingPrice: spec.sellingPrice } })
  await ok('POST', `/api/vendor/inventory/${productId}/adjustments`, {
    token,
    body: { type: 'SET', quantity: spec.stock, reason: 'STOCK_COUNT' },
  })
  await until(
    `"${spec.name}" is searchable, priced and in stock`,
    async () => {
      const result = await call('GET', `/api/search/products?q=${encodeURIComponent(spec.name)}&inStock=true`)
      const hits: Json[] = result.body?.content ?? []
      return hits.some((hit) => hit.id === productId && hit.inStock !== false)
    },
    { timeoutMs: 45_000 },
  )
  return productId
}

/** A public store coupon: `value` rupees off from `minOrderSubtotal`, valid for a day. */
export async function createStoreCoupon(store: Store, code: string, value: number, minOrderSubtotal: number): Promise<void> {
  const validUntil = new Date(Date.now() + 24 * 3600_000).toISOString().replace(/\.\d{3}Z$/, 'Z')
  await ok('POST', '/api/vendor/coupons', {
    token: store.session.token,
    body: {
      code,
      name: `E2E ${value} off`,
      scope: 'STORE',
      discountType: 'FLAT',
      value,
      minOrderSubtotal,
      validUntil,
      perCustomerLimit: 5,
      public: true,
    },
  })
}

export interface Customer {
  session: Session
  addressId: string
  name: string
  location: GeoPoint
}

/** A customer whose profile exists, optionally with a default address at `location`. */
export async function createCustomer(tag: string, name: string, location: GeoPoint | null): Promise<Customer> {
  const session = await register('CUSTOMER', testEmail('customer', tag), name)
  await until('the customer profile', async () => (await call('GET', '/api/customers/me', { token: session.token })).status === 200)
  let addressId = ''
  if (location) {
    const address = await ok('POST', '/api/customers/me/addresses', {
      token: session.token,
      body: {
        label: 'Home',
        recipientName: name,
        phone: '+919876543210',
        line1: '12 Lake View Layout',
        city: 'Hubballi',
        state: 'Karnataka',
        postalCode: '580020',
        country: 'IN',
        latitude: location.latitude,
        longitude: location.longitude,
        isDefault: true,
      },
    })
    addressId = String(address.id)
  }
  return { session, addressId, name, location: location ?? { latitude: 0, longitude: 0 } }
}

export interface Partner {
  session: Session
  partnerId: string
  name: string
}

/** An approved delivery partner (offline until they go online in the app). */
export async function createApprovedPartner(tag: string, firstName: string): Promise<Partner> {
  const name = `${firstName} Rider`
  const session = await register('DELIVERY_PARTNER', testEmail('rider', tag), name)
  const partnerId = await until('the partner profile', async () => {
    const result = await call('GET', '/api/delivery/partners/me', { token: session.token })
    return result.status === 200 ? String(result.body.id) : null
  })
  await ok('PUT', '/api/delivery/partners/me', {
    token: session.token,
    body: { fullName: name, phone: '+919800000000', city: 'Hubballi', vehicleType: 'BICYCLE' },
  })
  await ok('POST', '/api/delivery/partners/me/submit', { token: session.token })
  await ok('POST', `/api/admin/delivery-partners/${partnerId}/approve`, { token: (await admin()).token })
  await until('the partner is approved', async () => {
    const result = await call('GET', '/api/delivery/partners/me', { token: session.token })
    return result.status === 200 && ['ACTIVE', 'APPROVED'].includes(String(result.body.status))
  })
  return { session, partnerId, name }
}

export interface PlacedOrder {
  orderId: string
  orderNumber: string
  total: number
}

/** Fills the cart with `quantity` × product, places the order and pays it through the simulator: CONFIRMED. */
export async function placePaidOrder(customer: Customer, productId: string, quantity: number): Promise<PlacedOrder> {
  const token = customer.session.token
  await ok('DELETE', '/api/cart', { token }).catch(() => undefined)
  await ok('POST', '/api/cart/items', { token, body: { type: 'PRODUCT', productId, quantity } })
  const preview = await ok('GET', `/api/cart/preview?addressId=${customer.addressId}`, { token })
  if (!preview.canCheckout) throw new Error(`checkout blocked: ${JSON.stringify(preview.issues)}`)
  const order = await ok('POST', '/api/orders', {
    token,
    body: { addressId: customer.addressId, expectedTotal: preview.total, note: 'E2E order' },
    headers: { 'Idempotency-Key': `e2e-${RUN_ID}-${productId.slice(-8)}-${Date.now()}` },
  })
  const orderId = String(order.id)
  await until('a checkout session', async () => (await call('GET', `/api/payments/orders/${orderId}/checkout`, { token })).status === 200)
  const simulated = await ok('POST', `/api/payments/simulator/orders/${orderId}/pay`, { token, body: { outcome: 'SUCCESS' } })
  await ok('POST', '/api/payments/razorpay/verify', {
    token,
    body: {
      razorpayOrderId: simulated.razorpayOrderId,
      razorpayPaymentId: simulated.razorpayPaymentId,
      razorpaySignature: simulated.razorpaySignature,
    },
  })
  await until('the order is CONFIRMED', async () => (await ok('GET', `/api/orders/${orderId}`, { token })).status === 'CONFIRMED')
  return { orderId, orderNumber: String(order.orderNumber), total: Number(preview.total) }
}

export async function orderOf(customer: Customer, orderId: string): Promise<Json> {
  return ok('GET', `/api/orders/${orderId}`, { token: customer.session.token })
}

export interface ChatResult {
  status: number
  conversationId: string | null
  /** `data` of every `approval` event (the approval cards the assistant produced). */
  approvals: Json[]
  /** `data` of every `tool` event. */
  tools: Json[]
  raw: string
}

/** One assistant turn through the API (SSE answer read to the end), e.g. to make an admin propose an action. */
export async function chat(agent: string, token: string, message: string, conversationId?: string): Promise<ChatResult> {
  const result = await call('POST', `/api/ai/agents/${agent}/chat`, {
    token,
    body: conversationId ? { message, conversationId } : { message },
    headers: { Accept: 'text/event-stream' },
    timeoutMs: 90_000,
  })
  const events: { event: string; data: Json }[] = []
  for (const block of result.text.split(/\r?\n\r?\n/)) {
    const event = /^event:\s*(.+)$/m.exec(block)?.[1]?.trim()
    const data = block
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.replace(/^data:\s?/, ''))
      .join('\n')
    if (!event) continue
    let parsed: Json = data
    try {
      parsed = JSON.parse(data)
    } catch {
      // token events carry plain text
    }
    events.push({ event, data: parsed })
  }
  const of = (name: string) => events.filter((e) => e.event === name).map((e) => e.data)
  return {
    status: result.status,
    conversationId: of('conversation')[0]?.conversationId ?? null,
    approvals: of('approval'),
    tools: of('tool'),
    raw: result.text,
  }
}

/** Best-effort cleanup: an admin cancels an order that is still open (refunds it when paid). */
export async function cancelIfOpen(orderId: string | undefined): Promise<void> {
  if (!orderId) return
  await call('POST', `/api/admin/orders/${orderId}/cancel`, {
    token: (await admin()).token,
    body: { reason: 'E2E cleanup' },
  }).catch(() => undefined)
}
