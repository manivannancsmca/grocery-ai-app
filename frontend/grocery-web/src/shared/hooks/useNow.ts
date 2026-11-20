import { useEffect, useState } from 'react'

function nowMs(): number {
  return Date.now()
}

/** The current time, updated every `intervalMs` while `enabled` (countdowns). */
export function useNow(intervalMs = 1_000, enabled = true): number {
  const [now, setNow] = useState(nowMs)

  useEffect(() => {
    if (!enabled) return
    const timer = setInterval(() => setNow(nowMs()), intervalMs)
    return () => clearInterval(timer)
  }, [intervalMs, enabled])

  return now
}
