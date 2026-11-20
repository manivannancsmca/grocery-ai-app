import 'leaflet/dist/leaflet.css'
import { divIcon, map as createMap, marker as createMarker, polyline as createPolyline, tileLayer } from 'leaflet'
import type { DivIcon, LatLngTuple, Map as LeafletMap, Marker as LeafletMarker, Polyline as LeafletPolyline } from 'leaflet'
import { useEffect, useEffectEvent, useRef } from 'react'
import { iconMarkup, type IconName } from '../../icons'
import { cn } from '../../utils/cn'
import { DEFAULT_MAP_CENTER, type DeliveryMapProps, type GeoPoint, type MapPoint, type MapPointKind, roundCoordinate } from './types'

/** OpenStreetMap's standard tiles; their usage policy requires the attribution below to stay visible. */
const TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
const ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'

/** Each kind's colour (marker ring and legend dot) and picture. */
const KIND_STYLES: Record<MapPointKind, { ring: string; dot: string; icon: IconName }> = {
  store: { ring: 'border-amber-500', dot: 'bg-amber-500', icon: 'store' },
  restaurant: { ring: 'border-rose-500', dot: 'bg-rose-500', icon: 'cloche' },
  home: { ring: 'border-emerald-600', dot: 'bg-emerald-600', icon: 'home' },
  partner: { ring: 'border-sky-600', dot: 'bg-sky-600', icon: 'scooter' },
}

/** Plain HTML markers (`L.divIcon`): no image files, so the bundler cannot break Leaflet's default icon paths. */
function markerIcon(kind: MapPointKind): DivIcon {
  const style = KIND_STYLES[kind]
  return divIcon({
    // The partner glides between reported positions; Leaflet's own zoom animation still wins while zooming.
    className: cn('delivery-map-marker', kind === 'partner' && 'transition-transform duration-1000 ease-linear'),
    html: `<span class="flex size-9 items-center justify-center rounded-full border-[3px] bg-white shadow-md ${style.ring}">${iconMarkup(style.icon, 'size-5')}</span>`,
    iconSize: [36, 36],
    iconAnchor: [18, 18],
  })
}

/** Created on first use (not at import), so importing this module never touches Leaflet. */
let icons: Record<MapPointKind, DivIcon> | null = null

function iconFor(kind: MapPointKind): DivIcon {
  icons ??= { store: markerIcon('store'), restaurant: markerIcon('restaurant'), home: markerIcon('home'), partner: markerIcon('partner') }
  return icons[kind]
}

const toTuple = (point: GeoPoint): LatLngTuple => [point.latitude, point.longitude]

/** The route line: a dashed emerald stroke under the markers. */
const ROUTE_STYLE = { color: '#059669', weight: 4, opacity: 0.85, dashArray: '8 10', lineCap: 'round', interactive: false } as const

/** The positions the route passes through, in order (ids without a point on the map are skipped). */
function routeLatLngs(points: readonly MapPoint[], route: readonly string[] | undefined): LatLngTuple[] {
  if (!route) return []
  const byId = new Map(points.map((point) => [point.id, point]))
  return route.flatMap((id) => {
    const point = byId.get(id)
    return point ? [toTuple(point)] : []
  })
}

/** A marker on the map and what it was drawn with (to update it in place). */
interface PlacedMarker {
  marker: LeafletMarker
  kind: MapPointKind
  label: string
}

/**
 * Leaflet + OpenStreetMap map with store, home and partner markers (Phase 9), on plain `leaflet`:
 * - the `L.map` is created once per mount on a div and removed on unmount (StrictMode's double mount
 *   creates, removes and creates again on the same div);
 * - markers live in a ref and move with `setLatLng` when a point moves — a location ping never re-creates
 *   the map or the marker; the partner marker glides (CSS transition);
 * - the view fits all points when the set of points changes (a partner appears), not on every move; a
 *   partner leaving the visible area brings the whole route back into view;
 * - an optional route line (`route`: point ids in travel order) follows the points as they move.
 * A legend under the map names every point, so the map is never the only way to read where things are.
 * Keep it out of the main bundle: use `LazyDeliveryMap`.
 */
export function DeliveryMap({ points, label, onPick, fallbackCenter = DEFAULT_MAP_CENTER, className, route }: DeliveryMapProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<LeafletMap | null>(null)
  const markersRef = useRef(new Map<string, PlacedMarker>())
  const routeRef = useRef<LeafletPolyline | null>(null)

  /** Where the map opens; read once when it is created. */
  const initialCenter = useEffectEvent((): LatLngTuple => toTuple(points[0] ?? fallbackCenter))
  const pick = useEffectEvent((latitude: number, longitude: number) =>
    onPick?.({ latitude: roundCoordinate(latitude), longitude: roundCoordinate(longitude) }),
  )

  // The map itself: once per mount.
  useEffect(() => {
    const container = containerRef.current
    if (!container) return
    const map = createMap(container, { center: initialCenter(), zoom: 13, scrollWheelZoom: false })
    tileLayer(TILE_URL, { attribution: ATTRIBUTION, maxZoom: 19 }).addTo(map)
    map.on('click', (event) => pick(event.latlng.lat, event.latlng.lng))
    mapRef.current = map
    const markers = markersRef.current
    return () => {
      // Removes the tiles, the markers and every listener; the div can host a new map afterwards.
      map.remove()
      mapRef.current = null
      routeRef.current = null
      markers.clear()
    }
  }, [])

  // Markers follow the points: added, moved in place, relabelled or removed — never rebuilt for a move.
  const syncMarkers = useEffectEvent(() => {
    const map = mapRef.current
    if (!map) return
    const markers = markersRef.current
    const wanted = new Set(points.map((point) => point.id))
    for (const [id, placed] of markers) {
      if (wanted.has(id)) continue
      placed.marker.remove()
      markers.delete(id)
    }
    for (const point of points) {
      const placed = markers.get(point.id)
      if (!placed) {
        const marker = createMarker(toTuple(point), { icon: iconFor(point.kind), title: point.label, keyboard: false, interactive: false })
        markers.set(point.id, { marker: marker.addTo(map), kind: point.kind, label: point.label })
        continue
      }
      placed.marker.setLatLng(toTuple(point))
      if (placed.label !== point.label) {
        placed.marker.options.title = point.label
        placed.marker.getElement()?.setAttribute('title', point.label)
        placed.label = point.label
      }
      if (placed.kind !== point.kind) {
        placed.marker.setIcon(iconFor(point.kind))
        placed.kind = point.kind
      }
    }
  })

  // The route line follows its points: drawn once there are two, moved in place, removed when it has fewer.
  const syncRoute = useEffectEvent(() => {
    const map = mapRef.current
    if (!map) return
    const latLngs = routeLatLngs(points, route)
    if (latLngs.length < 2) {
      routeRef.current?.remove()
      routeRef.current = null
      return
    }
    if (routeRef.current) routeRef.current.setLatLngs(latLngs)
    else routeRef.current = createPolyline(latLngs, ROUTE_STYLE).addTo(map)
  })

  const fitAll = useEffectEvent(() => {
    const map = mapRef.current
    if (!map || points.length === 0) return
    if (points.length === 1) {
      map.setView(toTuple(points[0] as MapPoint), Math.max(map.getZoom(), 15))
      return
    }
    map.fitBounds(points.map(toTuple), { padding: [36, 36], maxZoom: 16 })
  })

  const keepPartnerInView = useEffectEvent(() => {
    const map = mapRef.current
    const partner = points.find((point) => point.kind === 'partner')
    if (!map || !partner || points.length < 2) return
    if (!map.getBounds().contains(toTuple(partner))) fitAll()
  })

  const drawn = points.map((point) => `${point.id}:${point.kind}:${point.latitude},${point.longitude}:${point.label}`).join('|')
  const pointSet = points.map((point) => point.id).join('|')
  const partner = points.find((point) => point.kind === 'partner')
  const partnerAt = partner ? `${partner.latitude},${partner.longitude}` : ''

  // Declared after the map effect, so they run after it (also when StrictMode mounts twice).
  const routeAt = routeLatLngs(points, route).join(';')

  useEffect(() => syncMarkers(), [drawn])
  useEffect(() => syncRoute(), [routeAt])
  useEffect(() => fitAll(), [pointSet])
  useEffect(() => keepPartnerInView(), [partnerAt])

  return (
    <figure aria-label={label} className="space-y-2">
      {/* `isolate` keeps Leaflet's high z-indexes (panes, controls) below the app's header and dialogs. */}
      <div className={cn('isolate overflow-hidden rounded-xl border border-slate-200 bg-slate-100', className ?? 'h-64')}>
        <div ref={containerRef} className="size-full" />
      </div>
      {points.length > 0 && (
        <figcaption>
          <ul aria-label="Map legend" className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-600">
            {points.map((point) => (
              <li key={point.id} className="flex items-center gap-1.5">
                <span aria-hidden="true" className={cn('size-2.5 rounded-full', KIND_STYLES[point.kind].dot)} />
                {point.label}
              </li>
            ))}
            {routeAt.includes(';') && (
              <li className="flex items-center gap-1.5">
                <span aria-hidden="true" className="w-4 border-t-2 border-dashed border-emerald-600" />
                Route (straight line)
              </li>
            )}
          </ul>
        </figcaption>
      )}
    </figure>
  )
}
