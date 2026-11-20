import { useCallback, useSyncExternalStore } from 'react'

const hasMatchMedia = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function'

/**
 * Whether a CSS media query matches (e.g. `(max-width: 639px)`), following changes. Without `matchMedia` (jsdom in
 * tests, server rendering) it is `fallback`, so a page renders its desktop layout there.
 * @param {string} query
 * @param [fallback=false]
 * @returns {boolean}
 */
export function useMediaQuery(query, fallback = false) {
  const subscribe = useCallback(
    (onChange) => {
      if (!hasMatchMedia()) return () => {}
      const list = window.matchMedia(query)
      list.addEventListener?.('change', onChange)
      return () => list.removeEventListener?.('change', onChange)
    },
    [query],
  )
  return useSyncExternalStore(
    subscribe,
    () => (hasMatchMedia() ? window.matchMedia(query).matches : fallback),
    () => fallback,
  )
}
