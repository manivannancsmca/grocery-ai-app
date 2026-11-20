import { useCallback, useState } from 'react'

/**
 * @typedef {object} Coordinates
 * @property {number} latitude
 * @property {number} longitude
 */

/** @typedef {{ status: 'idle' } | { status: 'locating' } | { status: 'error', message: string }} LocateState */

/** What the screen offers instead of the browser's location, e.g. "type the coordinates". */
const DEFAULT_ALTERNATIVE = 'type the coordinates'

/**
 * @param {GeolocationPositionError} error
 * @param {string} alternative
 * @returns {string}
 */
function geolocationMessage(error, alternative) {
  switch (error.code) {
    case error.PERMISSION_DENIED:
      return `Location permission was denied. Allow it in your browser, or ${alternative}.`
    case error.POSITION_UNAVAILABLE:
      return `Your location is not available right now. You can ${alternative} instead.`
    case error.TIMEOUT:
      return `Finding your location took too long. Try again, or ${alternative}.`
    default:
      return `Could not get your location. You can ${alternative} instead.`
  }
}

/**
 * Rounds to 6 decimals (~10 cm), which is what the API stores and plenty for deliveries.
 * @param {number} value
 * @returns {number}
 */
function round(value) {
  return Math.round(value * 1e6) / 1e6
}

/**
 * One-shot browser geolocation ("use my current location"). The browser asks for permission on the first call;
 * a refusal or failure becomes `error`, which suggests `alternative` (what the screen offers instead).
 * @param {string} [alternative=DEFAULT_ALTERNATIVE]
 */
export function useCurrentPosition(alternative = DEFAULT_ALTERNATIVE) {
  const [state, setState] = useState({ status: 'idle' })

  const locate = useCallback(() => {
    if (typeof navigator === 'undefined' || !('geolocation' in navigator)) {
      setState({ status: 'error', message: `This browser cannot share your location. You can ${alternative} instead.` })
      return Promise.resolve(null)
    }
    setState({ status: 'locating' })
    return new Promise((resolve) => {
      navigator.geolocation.getCurrentPosition(
        (position) => {
          setState({ status: 'idle' })
          resolve({ latitude: round(position.coords.latitude), longitude: round(position.coords.longitude) })
        },
        (error) => {
          setState({ status: 'error', message: geolocationMessage(error, alternative) })
          resolve(null)
        },
        { enableHighAccuracy: true, timeout: 15_000, maximumAge: 60_000 },
      )
    })
  }, [alternative])

  return {
    locate,
    isLocating: state.status === 'locating',
    error: state.status === 'error' ? state.message : null,
  }
}
