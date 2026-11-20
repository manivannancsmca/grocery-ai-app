import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach, beforeEach, vi } from 'vitest'
import { resetSessionForTests } from '../shared/auth/session'
import { mockApi } from './mockApi'
import { mockEventStream } from './mockEventStream'

// Phase 9 maps: no test ever loads Leaflet or a map tile. Markers and the attribution render as plain elements.
vi.mock('leaflet', () => import('./leafletMock'))

// Route pages are lazy-loaded; the first import of a page in a test file is transformed on demand,
// which can exceed the 1 s default on a Docker bind mount.
configure({ asyncUtilTimeout: 5_000 })

beforeEach(() => {
  // Every test starts signed out, with no real network: unmocked requests answer 501.
  resetSessionForTests('anonymous')
  mockApi({})
  // Signed-in renders open the live-updates stream: an in-memory one (200, silent) unless a test sets its own.
  mockEventStream()
  // The checkout attempt's Idempotency-Key lives in sessionStorage; no test may inherit another's.
  sessionStorage.clear()
  // Phase 15: a delivery location chosen in one test must not follow the next one.
  for (const key of Object.keys(localStorage)) if (key.startsWith('freshcart.deliverTo.')) localStorage.removeItem(key)
})

afterEach(() => {
  cleanup()
})
