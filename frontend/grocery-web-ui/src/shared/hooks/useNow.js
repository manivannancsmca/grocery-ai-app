import { useEffect, useState } from 'react'

/** @returns {number} */
function nowMs() {
  return Date.now()
}

/**
 * The current time, updated every `intervalMs` while `enabled` (countdowns).
 * @param [intervalMs=1_000]
 * @param [enabled=true]
 * @returns {number}
 */
export function useNow(intervalMs = 1_000, enabled = true) {
  const [now, setNow] = useState(nowMs)

  useEffect(() => {
    if (!enabled) return
    const timer = setInterval(() => setNow(nowMs()), intervalMs)
    return () => clearInterval(timer)
  }, [intervalMs, enabled])

  return now
}
