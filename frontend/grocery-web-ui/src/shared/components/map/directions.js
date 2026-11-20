/** @typedef {import('./types').GeoPoint} GeoPoint */

/**
 * Turn-by-turn directions to a point in Google Maps (its public "Maps URLs" format: no API key). Without an origin,
 * Google Maps starts from the phone's own location, and on a phone the link opens the Maps app. The coordinates go
 * to Google only when the partner taps the link.
 * @param {GeoPoint} destination
 * @returns {string}
 */
export function directionsUrl(destination) {
  const params = new URLSearchParams({ api: '1', destination: `${destination.latitude},${destination.longitude}` })
  return `https://www.google.com/maps/dir/?${params.toString()}`
}
