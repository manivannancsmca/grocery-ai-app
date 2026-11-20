import type { CartStore } from './api'

/** `1 item`, `3 items`. */
export function itemsLabel(count: number): string {
  return `${count} item${count === 1 ? '' : 's'}`
}

/** Where the store's items can be browsed: the restaurant page, or the vendor's products in search. */
export function storePath(store: CartStore): string {
  return store.type === 'RESTAURANT'
    ? `/restaurants/${encodeURIComponent(store.id)}`
    : `/search?vendorId=${encodeURIComponent(store.id)}`
}
