import { type RefObject, useEffect, useState } from 'react'

/**
 * The element's width in CSS pixels, kept up to date as it resizes. `ResizeObserver` reports the first size right
 * after `observe()`; where it does not exist (tests), the chart lays out at `fallback`.
 */
export function useElementWidth(ref: RefObject<HTMLElement | null>, fallback = 640): number {
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
