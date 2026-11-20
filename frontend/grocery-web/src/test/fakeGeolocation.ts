import { act } from '@testing-library/react'
import { vi } from 'vitest'

interface Watcher {
  success: PositionCallback
  error: PositionErrorCallback | null | undefined
}

/**
 * A scripted `navigator.geolocation` (jsdom has none): records `watchPosition` / `clearWatch`, and lets the test
 * move the device or deny the permission. Undo it with `uninstall()`.
 */
export function installFakeGeolocation() {
  const watchers = new Map<number, Watcher>()
  let nextId = 1
  const geolocation = {
    watchPosition: vi.fn((success: PositionCallback, error?: PositionErrorCallback | null) => {
      const id = nextId++
      watchers.set(id, { success, error })
      return id
    }),
    clearWatch: vi.fn((id: number) => {
      watchers.delete(id)
    }),
    getCurrentPosition: vi.fn(),
  }
  Object.defineProperty(navigator, 'geolocation', { value: geolocation, configurable: true })

  const emit = (run: (watcher: Watcher) => void) =>
    act(() => {
      for (const watcher of [...watchers.values()]) run(watcher)
    })

  return {
    geolocation,
    /** How many watches are open right now. */
    get watching() {
      return watchers.size
    },
    /** The device reports a new fix to every open watch. */
    move(latitude: number, longitude: number, extra: { accuracy?: number; heading?: number | null; speed?: number | null } = {}) {
      const coords = {
        latitude,
        longitude,
        accuracy: extra.accuracy ?? 12,
        heading: extra.heading ?? null,
        speed: extra.speed ?? null,
        altitude: null,
        altitudeAccuracy: null,
      }
      emit((watcher) => watcher.success({ coords, timestamp: Date.now() } as unknown as GeolocationPosition))
    },
    /** The user blocked location for the site. */
    deny() {
      const error = { code: 1, message: 'User denied Geolocation', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 }
      emit((watcher) => watcher.error?.(error as GeolocationPositionError))
    },
    uninstall() {
      Reflect.deleteProperty(navigator, 'geolocation')
    },
  }
}
