/** @typedef {import('./api').CartStore} CartStore */

/**
 * `1 item`, `3 items`.
 * @param {number} count
 * @returns {string}
 */
export function itemsLabel(count) {
  return `${count} item${count === 1 ? '' : 's'}`
}

/**
 * Where the store's items can be browsed: the restaurant page, or the vendor's products in search.
 * @param {CartStore} store
 * @returns {string}
 */
export function storePath(store) {
  return store.type === 'RESTAURANT'
    ? `/restaurants/${encodeURIComponent(store.id)}`
    : `/search?vendorId=${encodeURIComponent(store.id)}`
}
