import { act } from '@testing-library/react'
import { vi } from 'vitest'

/**
 * A scripted `navigator.geolocation` (jsdom has none): records `watchPosition` / `clearWatch`, and lets the test
 * move the device or deny the permission. Undo it with `uninstall()`.
 */
export function installFakeGeolocation() {
  const watchers = new Map()
  let nextId = 1
  const geolocation = {
    watchPosition: vi.fn((success, error) => {
      const id = nextId++
      watchers.set(id, { success, error })
      return id
    }),
    clearWatch: vi.fn((id) => {
      watchers.delete(id)
    }),
    getCurrentPosition: vi.fn(),
  }
  Object.defineProperty(navigator, 'geolocation', { value: geolocation, configurable: true })

  const emit = (run) =>
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
    move(latitude, longitude, extra = {}) {
      const coords = {
        latitude,
        longitude,
        accuracy: extra.accuracy ?? 12,
        heading: extra.heading ?? null,
        speed: extra.speed ?? null,
        altitude: null,
        altitudeAccuracy: null,
      }
      emit((watcher) => watcher.success({ coords, timestamp: Date.now() }))
    },
    /** The user blocked location for the site. */
    deny() {
      const error = {
        code: 1,
        message: 'User denied Geolocation',
        PERMISSION_DENIED: 1,
        POSITION_UNAVAILABLE: 2,
        TIMEOUT: 3,
      }
      emit((watcher) => watcher.error?.(error))
    },
    uninstall() {
      Reflect.deleteProperty(navigator, 'geolocation')
    },
  }
}
