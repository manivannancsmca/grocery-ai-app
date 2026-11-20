import { act, render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { clickMap, leafletStats } from '../../../test/leafletMock'
import { DeliveryMap } from './DeliveryMap'

const store = { id: 'store', kind: 'store', label: 'Green Basket (store)', latitude: 12.9716, longitude: 77.5946 }
const home = { id: 'home', kind: 'home', label: 'Your address', latitude: 12.9784, longitude: 77.6408 }
const partnerAt = (latitude, longitude) => ({
  id: 'partner',
  kind: 'partner',
  label: 'Ravi (delivery partner)',
  latitude,
  longitude,
})

function snapshot() {
  return { ...leafletStats }
}

describe('DeliveryMap (plain Leaflet)', () => {
  it('creates one live map in StrictMode, moves markers in place, refits only when the set of points changes, and removes the map on unmount', () => {
    const before = snapshot()
    const view = (points) => (
      <StrictMode>
        <DeliveryMap points={points} label="Delivery map" />
      </StrictMode>
    )
    const { rerender, unmount } = render(view([store, home]))

    // StrictMode mounts twice: created, removed, created again on the same div — one live map.
    expect(leafletStats.mapsCreated - before.mapsCreated - (leafletStats.mapsRemoved - before.mapsRemoved)).toBe(1)
    expect(screen.getAllByTestId('leaflet-map')).toHaveLength(1)
    expect(screen.getByRole('figure', { name: 'Delivery map' })).toBeInTheDocument()
    expect(screen.getByTestId('map-attribution')).toHaveTextContent('© OpenStreetMap contributors')
    expect(screen.getAllByTestId('map-marker')).toHaveLength(2)
    expect(screen.getByTitle('Green Basket (store)')).toHaveAttribute('data-latitude', '12.9716')
    expect(screen.getByRole('list', { name: 'Map legend' })).toHaveTextContent('Your address')

    // A partner appears: one new marker and a new fit.
    const fits = leafletStats.fits
    rerender(view([store, home, partnerAt(12.965, 77.59)]))
    const partner = screen.getByTitle('Ravi (delivery partner)')
    expect(leafletStats.fits).toBeGreaterThan(fits)

    // The partner moves: same map, same marker element, new position, no refit.
    const afterAppear = snapshot()
    rerender(view([store, home, partnerAt(12.97, 77.6)]))
    expect(screen.getByTitle('Ravi (delivery partner)')).toBe(partner)
    expect(partner).toHaveAttribute('data-latitude', '12.97')
    expect(partner).toHaveAttribute('data-longitude', '77.6')
    expect(leafletStats.mapsCreated).toBe(afterAppear.mapsCreated)
    expect(leafletStats.markersCreated).toBe(afterAppear.markersCreated)
    expect(leafletStats.fits).toBe(afterAppear.fits)

    // The partner leaves: its marker goes.
    rerender(view([store, home]))
    expect(screen.queryByTitle('Ravi (delivery partner)')).not.toBeInTheDocument()

    const removed = leafletStats.mapsRemoved
    unmount()
    expect(leafletStats.mapsRemoved).toBe(removed + 1)
    expect(leafletStats.mapsCreated - before.mapsCreated).toBe(leafletStats.mapsRemoved - before.mapsRemoved)
  })

  it('reports map clicks as rounded positions when `onPick` is given', () => {
    const onPick = vi.fn()
    render(<DeliveryMap points={[]} label="Simulator map" onPick={onPick} />)

    act(() => clickMap(12.123456789, 77.987654321))
    expect(onPick).toHaveBeenCalledWith({ latitude: 12.123457, longitude: 77.987654 })
    expect(screen.queryByRole('list', { name: 'Map legend' })).not.toBeInTheDocument()
  })

  it('draws the route through the given points in order, moves it with the partner and drops it when too short', () => {
    const restaurant = {
      id: 'store',
      kind: 'restaurant',
      label: 'Spice Route (restaurant)',
      latitude: 12.9716,
      longitude: 77.5946,
    }
    const view = (points) => <DeliveryMap points={points} route={['partner', 'store', 'home']} label="Delivery map" />
    const { rerender } = render(view([restaurant, home]))

    // No partner yet: restaurant → home (the missing id is skipped).
    const line = screen.getByTestId('map-route')
    expect(line).toHaveAttribute('data-points', '12.9716,77.5946;12.9784,77.6408')
    expect(screen.getByTitle('Spice Route (restaurant)')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Map legend' })).toHaveTextContent('Route (straight line)')

    // The partner appears and moves: the same line follows, starting at the partner.
    const routes = leafletStats.routesCreated
    rerender(view([restaurant, home, partnerAt(12.965, 77.59)]))
    rerender(view([restaurant, home, partnerAt(12.968, 77.592)]))
    expect(screen.getByTestId('map-route')).toBe(line)
    expect(line).toHaveAttribute('data-points', '12.968,77.592;12.9716,77.5946;12.9784,77.6408')
    expect(leafletStats.routesCreated).toBe(routes)

    // A single point is no route.
    rerender(view([home]))
    expect(screen.queryByTestId('map-route')).not.toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Map legend' })).not.toHaveTextContent('Route')
  })
})
