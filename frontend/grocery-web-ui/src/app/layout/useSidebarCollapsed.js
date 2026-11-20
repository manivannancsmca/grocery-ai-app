import { useCallback, useState } from 'react'

const STORAGE_KEY = 'freshcart.sidebar'

/** @returns {boolean} */
function readCollapsed() {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === 'collapsed'
  } catch {
    return false // private window or blocked storage: start expanded
  }
}

/**
 * Whether the desktop sidebar shows only icons. The layout stays mounted while the user moves between pages, so the
 * choice holds while navigating; it is also remembered in this browser for the next visit (a per-viewer convenience:
 * without storage the menu simply starts expanded).
 * @returns {[ boolean, () => void ]}
 */
export function useSidebarCollapsed() {
  const [collapsed, setCollapsed] = useState(readCollapsed)
  const toggle = useCallback(() => {
    setCollapsed((current) => {
      const next = !current
      try {
        window.localStorage.setItem(STORAGE_KEY, next ? 'collapsed' : 'expanded')
      } catch {
        // not remembered: fine
      }
      return next
    })
  }, [])
  return [collapsed, toggle]
}
