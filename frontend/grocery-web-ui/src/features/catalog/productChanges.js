import { useEffect, useState } from 'react'

/** @typedef {import('./api').ProductCard} ProductCard */

/**
 * What a live refresh changed about a product the customer is looking at.
 * @typedef {'PRICE_DROP' | 'PRICE_RISE' | 'BACK_IN_STOCK' | 'SOLD_OUT'} ProductChange
 */

/** How long a changed product stays highlighted. */
export const CHANGE_HIGHLIGHT_MS = 8_000

/**
 * Stock transitions win over price moves; a price appearing or disappearing is not flagged.
 * @param {ProductCard} before
 * @param {ProductCard} after
 * @returns {ProductChange | null}
 */
export function productChange(before, after) {
  const wasOut = before.inStock === false
  const isOut = after.inStock === false
  if (!wasOut && isOut) return 'SOLD_OUT'
  if (wasOut && !isOut) return 'BACK_IN_STOCK'
  const was = before.price?.sellingPrice
  const now = after.price?.sellingPrice
  if (was == null || now == null || was === now) return null
  return now < was ? 'PRICE_DROP' : 'PRICE_RISE'
}

/**
 * Products in both lists whose stock or price changed, by id.
 * @param {readonly ProductCard[]} before
 * @param {readonly ProductCard[]} after
 * @returns {Map<string, ProductChange>}
 */
export function diffProducts(before, after) {
  const previous = new Map(before.map((product) => [product.id, product]))
  const changes = new Map()
  for (const product of after) {
    const old = previous.get(product.id)
    const change = old ? productChange(old, product) : null
    if (change) changes.set(product.id, change)
  }
  return changes
}

/** @type {ReadonlyMap<string, ProductChange>} */
const NO_CHANGES = new Map()

/**
 * @typedef {object} Tracked
 * @property {string} scope
 * @property {readonly ProductCard[] | undefined} products
 * @property {ReadonlyMap<string, ProductChange>} changes
 */

/**
 * Highlights products whose stock or price changed between two results of the same query (`scope`), e.g. after a
 * live `STOCK_CHANGED` refetch. A new scope (other filters or page) or `undefined` products (still loading,
 * placeholder data) start over without highlights, so switching filters never flags anything. Highlights clear
 * `CHANGE_HIGHLIGHT_MS` after the latest change.
 * @param {readonly ProductCard[] | undefined} products
 * @param {string} scope
 * @returns {ReadonlyMap<string, ProductChange>}
 */
export function useProductChanges(products, scope) {
  const [tracked, setTracked] = useState({ scope, products, changes: NO_CHANGES })

  if (tracked.scope !== scope || tracked.products !== products) {
    let changes = NO_CHANGES
    if (tracked.scope === scope && tracked.products && products) {
      const fresh = diffProducts(tracked.products, products)
      const onScreen = new Set(products.map((product) => product.id))
      // Earlier highlights stay while their product is still shown and has not changed again.
      const kept = [...tracked.changes].filter(([id]) => onScreen.has(id) && !fresh.has(id))
      changes =
        fresh.size === 0 && kept.length === tracked.changes.size ? tracked.changes : new Map([...kept, ...fresh])
    }
    setTracked({ scope, products, changes })
  }

  useEffect(() => {
    if (tracked.changes.size === 0) return
    const timer = window.setTimeout(
      () => setTracked((current) => ({ ...current, changes: NO_CHANGES })),
      CHANGE_HIGHLIGHT_MS,
    )
    return () => window.clearTimeout(timer)
  }, [tracked.changes])

  return tracked.changes
}
