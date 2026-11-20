import { useEffect, useState } from 'react'

/** @typedef {import('react').RefObject} RefObject */

/**
 * The element's width in CSS pixels, kept up to date as it resizes. `ResizeObserver` reports the first size right
 * after `observe()`; where it does not exist (tests), the chart lays out at `fallback`.
 * @param {RefObject<HTMLElement | null>} ref
 * @param [fallback=640]
 * @returns {number}
 */
export function useElementWidth(ref, fallback = 640) {
  const [width, setWidth] = useState(fallback)

  useEffect(() => {
    const element = ref.current
    if (!element || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(([entry]) => {
      const measured = entry?.contentRect.width ?? 0
      if (measured > 0) setWidth(Math.round(measured))
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [ref])

  return width
}
