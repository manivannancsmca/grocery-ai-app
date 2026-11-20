/**
 * Stand-in for `leaflet` in every test (installed in `setup.ts`): no real map, no tiles, no network. It
 * implements the few calls `DeliveryMap` makes and draws plain elements into the map's div that tests can
 * query — each marker is a `title`d element carrying its position (`data-latitude` / `data-longitude`), and
 * the tile layer shows its attribution as text. `leafletStats` counts maps and markers, so tests can prove a
 * location ping moves a marker instead of rebuilding the map.
 */

type LatLngTuple = [number, number]

interface ClickEvent {
  latlng: { lat: number; lng: number }
}

type ClickHandler = (event: ClickEvent) => void

const clickHandlers = new Set<ClickHandler>()

export const leafletStats = { mapsCreated: 0, mapsRemoved: 0, markersCreated: 0, fits: 0, routesCreated: 0 }

/** Plays a click on every live map at this position (the location simulator listens to map clicks). */
export function clickMap(latitude: number, longitude: number): void {
  for (const handler of [...clickHandlers]) handler({ latlng: { lat: latitude, lng: longitude } })
}

export function divIcon(options: Record<string, unknown>) {
  return { options }
}

class FakeMap {
  readonly container: HTMLElement
  private readonly handlers = new Set<ClickHandler>()
  private zoom: number

  constructor(container: HTMLElement, options: { zoom?: number } = {}) {
    if (container.dataset.testid === 'leaflet-map') throw new Error('Map container is already initialized.')
    this.container = container
    this.zoom = options.zoom ?? 13
    container.dataset.testid = 'leaflet-map'
    leafletStats.mapsCreated++
  }

  on(type: string, handler: ClickHandler) {
    if (type === 'click') {
      this.handlers.add(handler)
      clickHandlers.add(handler)
    }
    return this
  }

  setView(_center: LatLngTuple, zoom?: number) {
    if (zoom !== undefined) this.zoom = zoom
    return this
  }

  fitBounds() {
    leafletStats.fits++
    return this
  }

  getZoom() {
    return this.zoom
  }

  getBounds() {
    return { contains: () => true }
  }

  remove() {
    for (const handler of this.handlers) clickHandlers.delete(handler)
    this.handlers.clear()
    this.container.replaceChildren()
    delete this.container.dataset.testid
    leafletStats.mapsRemoved++
    return this
  }
}

export function map(container: HTMLElement, options?: { zoom?: number }) {
  return new FakeMap(container, options)
}

export function tileLayer(_url: string, options: { attribution?: string } = {}) {
  return {
    addTo(target: FakeMap) {
      const attribution = document.createElement('span')
      attribution.dataset.testid = 'map-attribution'
      attribution.textContent = (options.attribution ?? '').replace(/<[^>]+>/g, '').replace('&copy;', '©')
      target.container.append(attribution)
      return this
    },
  }
}

class FakeMarker {
  readonly options: { title?: string }
  private readonly element = document.createElement('span')

  constructor(latlng: LatLngTuple, options: { title?: string } = {}) {
    this.options = options
    this.element.dataset.testid = 'map-marker'
    if (options.title) this.element.setAttribute('title', options.title)
    this.setLatLng(latlng)
    leafletStats.markersCreated++
  }

  addTo(target: FakeMap) {
    target.container.append(this.element)
    return this
  }

  setLatLng([latitude, longitude]: LatLngTuple) {
    this.element.dataset.latitude = String(latitude)
    this.element.dataset.longitude = String(longitude)
    return this
  }

  setIcon() {
    return this
  }

  getElement() {
    return this.element
  }

  remove() {
    this.element.remove()
    return this
  }
}

export function marker(latlng: LatLngTuple, options?: { title?: string }) {
  return new FakeMarker(latlng, options)
}

/** A route line: one element carrying its points as `data-points="lat,lng;lat,lng"`. */
class FakePolyline {
  private readonly element = document.createElement('span')

  constructor(latlngs: LatLngTuple[]) {
    this.element.dataset.testid = 'map-route'
    this.setLatLngs(latlngs)
    leafletStats.routesCreated++
  }

  addTo(target: FakeMap) {
    target.container.append(this.element)
    return this
  }

  setLatLngs(latlngs: LatLngTuple[]) {
    this.element.dataset.points = latlngs.map(([latitude, longitude]) => `${latitude},${longitude}`).join(';')
    return this
  }

  remove() {
    this.element.remove()
    return this
  }
}

export function polyline(latlngs: LatLngTuple[]) {
  return new FakePolyline(latlngs)
}
