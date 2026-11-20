// =====================================================================================
// Local dev: rebuilds the search copy (Elasticsearch) from the services that own the data. Run it after a database
// import (host-run\db-restore.cmd), or whenever search shows data that does not match the services.
//
//   node infrastructure/local/scripts/rebuild-search.mjs
//
// 1. Empties the three search indexes (products, categories, restaurants). They must start empty: every owner
//    re-sends its data with the version it has, and search ignores a version that is not newer than its own. After
//    an import the services' versions are usually older than the ones search still holds.
// 2. Asks each owner to re-send everything (the same actions as admin → Catalog → Search index): catalog-command
//    (categories, products), restaurant (listings), pricing (prices), inventory (stock), review (ratings).
//    catalog-query-service creates the indexes again and fills them from those events.
// 3. Waits until the numbers of documents stop changing, and prints them next to the numbers from before.
//
// Prereq: the services are running. Node 18 or newer.
// Env: GATEWAY_URL (default http://localhost:8080), ELASTICSEARCH_URL (default http://localhost:9200),
//      BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD (default admin@grocery.local / Admin@12345).
// =====================================================================================
import { GATEWAY_URL, log, login, must, sleep } from './gateway-client.mjs'

const ELASTICSEARCH_URL = process.env.ELASTICSEARCH_URL ?? `http://localhost:${process.env.ES_HOST_PORT ?? 9200}`
const ADMIN = {
  email: process.env.BOOTSTRAP_ADMIN_EMAIL ?? 'admin@grocery.local',
  password: process.env.BOOTSTRAP_ADMIN_PASSWORD ?? 'Admin@12345',
}
const ALIASES = ['products', 'categories', 'restaurants']
const OWNERS = [
  ['categories and products', '/api/admin/catalog/republish'],
  ['restaurants', '/api/admin/restaurants/listings/republish'],
  ['prices', '/api/admin/pricing/republish'],
  ['stock', '/api/admin/inventory/republish'],
  ['ratings', '/api/admin/reviews/summaries/republish'],
]

/** Documents per search index: { products: 270, categories: 18, restaurants: 205 } (0 while an index is missing). */
async function counts(token) {
  const statuses = (await must('read the search indexes', 'GET', '/api/admin/search/indexes', token)).data
  return Object.fromEntries(ALIASES.map((alias) => [alias, statuses.find((s) => s.alias === alias)?.documents ?? 0]))
}

const describe = (numbers) => ALIASES.map((alias) => `${alias} ${numbers[alias]}`).join(', ')

const token = await login(ADMIN.email, ADMIN.password)
if (!token) {
  throw new Error(`Cannot sign in as ${ADMIN.email} through ${GATEWAY_URL}. Are the services running?`)
}
// Read first: nothing is deleted unless search (catalog-query-service) answers.
const before = await counts(token)
log(`Search before: ${describe(before)}`)

// 1. Empty the indexes (each alias points at a versioned index such as products_v1).
for (const alias of ALIASES) {
  const response = await fetch(`${ELASTICSEARCH_URL}/_alias/${alias}`)
  if (response.status === 404) continue
  if (!response.ok) throw new Error(`Elasticsearch at ${ELASTICSEARCH_URL}: HTTP ${response.status} ${await response.text()}`)
  for (const index of Object.keys(await response.json())) {
    const deleted = await fetch(`${ELASTICSEARCH_URL}/${index}`, { method: 'DELETE' })
    if (!deleted.ok) throw new Error(`Deleting the search index ${index}: HTTP ${deleted.status} ${await deleted.text()}`)
    log(`  emptied ${alias} (${index})`)
  }
}

// 2. Every owner re-sends its data.
const sent = {}
for (const [what, path] of OWNERS) {
  const result = await must(`re-send ${what}`, 'POST', path, token)
  Object.assign(sent, result.data)
  log(`  ✔ ${what} re-sent: ${JSON.stringify(result.data)}`)
}

// 3. Wait until the counts are the same three times in a row (events take a moment) and every index that should
//    have documents has some. Drafts are not indexed, so products can end up lower than the number re-sent.
const expectsDocuments = { products: sent.products > 0, categories: sent.categories > 0, restaurants: sent.restaurants > 0 }
let after = await counts(token)
for (let stable = 0, waited = 0; stable < 3 && waited < 120; waited += 2) {
  await sleep(2000)
  const now = await counts(token)
  const filled = ALIASES.every((alias) => !expectsDocuments[alias] || now[alias] > 0)
  stable = filled && describe(now) === describe(after) ? stable + 1 : 0
  after = now
}
log(`Search after:  ${describe(after)}`)
const fewer = ALIASES.filter((alias) => after[alias] < before[alias])
if (fewer.length > 0) {
  log(`Fewer documents than before in: ${fewer.join(', ')}. Expected after importing older data; otherwise check`)
  log('that every service is running and run this script again.')
}
