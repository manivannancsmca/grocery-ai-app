/**
 * Stand-in for `leaflet` in every test (installed in `setup.js`): no real map, no tiles, no network. It
 * implements the few calls `DeliveryMap` makes and draws plain elements into the map's div that tests can
 * query — each marker is a `title`d element carrying its position (`data-latitude` / `data-longitude`), and
 * the tile layer shows its attribution as text. `leafletStats` counts maps and markers, so tests can prove a
 * location ping moves a marker instead of rebuilding the map.
 */

const clickHandlers = new Set()

export const leafletStats = { mapsCreated: 0, mapsRemoved: 0, markersCreated: 0, fits: 0, routesCreated: 0 }

/** Plays a click on every live map at this position (the location simulator listens to map clicks). */
export function clickMap(latitude, longitude) {
  for (const handler of [...clickHandlers]) handler({ latlng: { lat: latitude, lng: longitude } })
}

export function divIcon(options) {
  return { options }
}

class FakeMap {
  container
  handlers = new Set()
  zoom

  constructor(container, options = {}) {
    if (container.dataset.testid === 'leaflet-map') throw new Error('Map container is already initialized.')
    this.container = container
    this.zoom = options.zoom ?? 13
    container.dataset.testid = 'leaflet-map'
    leafletStats.mapsCreated++
  }

  on(type, handler) {
    if (type === 'click') {
      this.handlers.add(handler)
      clickHandlers.add(handler)
    }
    return this
  }

  setView(_center, zoom) {
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

export function map(container, options) {
  return new FakeMap(container, options)
}

export function tileLayer(_url, options = {}) {
  return {
    addTo(target) {
      const attribution = document.createElement('span')
      attribution.dataset.testid = 'map-attribution'
      attribution.textContent = (options.attribution ?? '').replace(/<[^>]+>/g, '').replace('&copy;', '©')
      target.container.append(attribution)
      return this
    },
  }
}

class FakeMarker {
  options
  element = document.createElement('span')

  constructor(latlng, options = {}) {
    this.options = options
    this.element.dataset.testid = 'map-marker'
    if (options.title) this.element.setAttribute('title', options.title)
    this.setLatLng(latlng)
    leafletStats.markersCreated++
  }

  addTo(target) {
    target.container.append(this.element)
    return this
  }

  setLatLng([latitude, longitude]) {
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

export function marker(latlng, options) {
  return new FakeMarker(latlng, options)
}

/** A route line: one element carrying its points as `data-points="lat,lng;lat,lng"`. */
class FakePolyline {
  element = document.createElement('span')

  constructor(latlngs) {
    this.element.dataset.testid = 'map-route'
    this.setLatLngs(latlngs)
    leafletStats.routesCreated++
  }

  addTo(target) {
    target.container.append(this.element)
    return this
  }

  setLatLngs(latlngs) {
    this.element.dataset.points = latlngs.map(([latitude, longitude]) => `${latitude},${longitude}`).join(';')
    return this
  }

  remove() {
    this.element.remove()
    return this
  }
}

export function polyline(latlngs) {
  return new FakePolyline(latlngs)
}
