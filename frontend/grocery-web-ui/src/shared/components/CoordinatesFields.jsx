import { useCurrentPosition } from '../hooks/useCurrentPosition'
import { Icon } from '../icons'
import { Button, Spinner, TextField } from './ui'

/** @typedef {import('react-hook-form').UseFormRegisterReturn} UseFormRegisterReturn */

/**
 * @typedef {object} CoordinatesFieldsProps
 * @property {UseFormRegisterReturn} latitude
 * @property {UseFormRegisterReturn} longitude
 * @property {string} [latitudeError]
 * @property {string} [longitudeError]
 * @property {boolean} [required]
 * @property {boolean} [disabled]
 * @property {(coords: { latitude: string, longitude: string }) => void} onLocate Receives the browser position as form text values.
 */

/**
 * Latitude / longitude inputs plus a "Use my current location" button (browser geolocation).
 * @param {CoordinatesFieldsProps} props
 */
export function CoordinatesFields({
  latitude,
  longitude,
  latitudeError,
  longitudeError,
  required,
  disabled,
  onLocate,
}) {
  const { locate, isLocating, error } = useCurrentPosition()

  async function fillFromBrowser() {
    const coords = await locate()
    if (coords) onLocate({ latitude: String(coords.latitude), longitude: String(coords.longitude) })
  }

  return (
    <div className="space-y-3 sm:col-span-2">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <p className="text-sm font-medium text-slate-700">Map location</p>
        {!disabled && (
          <Button variant="secondary" size="sm" onClick={() => void fillFromBrowser()} disabled={isLocating}>
            {isLocating ? <Spinner size="sm" /> : <Icon name="crosshair" className="size-4" />}
            {isLocating ? 'Locating…' : 'Use my current location'}
          </Button>
        )}
      </div>
      {error && (
        <p role="alert" className="text-xs font-medium text-amber-700">
          {error}
        </p>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Latitude"
          inputMode="decimal"
          placeholder="12.9716"
          required={required}
          error={latitudeError}
          disabled={disabled}
          {...latitude}
        />
        <TextField
          label="Longitude"
          inputMode="decimal"
          placeholder="77.5946"
          required={required}
          error={longitudeError}
          disabled={disabled}
          {...longitude}
        />
      </div>
    </div>
  )
}
