import { useId, useState } from 'react'
import { Button, Input } from '../../shared/components/ui'
import { checkPriceRange } from './priceRange'

/** @typedef {import('./priceRange').PriceRange} PriceRange */

/**
 * @typedef {object} PriceRangeFilterProps
 * @property {PriceRange} value The range currently in the URL (re-mount the component with a `key` when it changes).
 * @property {(range: PriceRange) => void} onApply
 * @property {boolean} [disabled]
 */

/**
 * Min / max selling price, validated before it goes into the URL. Either bound may be left empty.
 * @param {PriceRangeFilterProps} props
 */
export function PriceRangeFilter({ value, onApply, disabled }) {
  const id = useId()
  const [min, setMin] = useState(value.minPrice === undefined ? '' : String(value.minPrice))
  const [max, setMax] = useState(value.maxPrice === undefined ? '' : String(value.maxPrice))
  const [errors, setErrors] = useState({})
  const active = value.minPrice !== undefined || value.maxPrice !== undefined

  function submit(event) {
    event.preventDefault()
    const check = checkPriceRange(min, max)
    setErrors(check.errors)
    if (!check.errors.min && !check.errors.max) onApply(check.range)
  }

  return (
    <section aria-labelledby={`${id}-title`}>
      <h3 id={`${id}-title`} className="mb-2.5 text-xs font-semibold tracking-wider text-slate-500 uppercase">
        Price
      </h3>
      <form onSubmit={submit} noValidate className="space-y-3">
        <div className="grid grid-cols-2 gap-2">
          <div>
            <label htmlFor={`${id}-min`} className="block text-xs font-medium text-slate-600">
              Min price (₹)
            </label>
            <Input
              id={`${id}-min`}
              inputMode="decimal"
              placeholder="0"
              className="mt-1 h-9"
              value={min}
              invalid={Boolean(errors.min)}
              aria-describedby={errors.min ? `${id}-min-error` : undefined}
              onChange={(event) => setMin(event.target.value)}
            />
          </div>
          <div>
            <label htmlFor={`${id}-max`} className="block text-xs font-medium text-slate-600">
              Max price (₹)
            </label>
            <Input
              id={`${id}-max`}
              inputMode="decimal"
              placeholder="Any"
              className="mt-1 h-9"
              value={max}
              invalid={Boolean(errors.max)}
              aria-describedby={errors.max ? `${id}-max-error` : undefined}
              onChange={(event) => setMax(event.target.value)}
            />
          </div>
        </div>
        {errors.min && (
          <p id={`${id}-min-error`} className="text-xs font-medium text-red-600">
            Min: {errors.min}
          </p>
        )}
        {errors.max && (
          <p id={`${id}-max-error`} className="text-xs font-medium text-red-600">
            Max: {errors.max}
          </p>
        )}
        <div className="flex gap-2">
          <Button
            type="submit"
            size="sm"
            variant="secondary"
            className="flex-1 text-emerald-700 ring-emerald-200 hover:bg-emerald-50 hover:ring-emerald-300"
            disabled={disabled}
          >
            Apply price
          </Button>
          {active && (
            <Button size="sm" variant="ghost" disabled={disabled} onClick={() => onApply({})}>
              Clear
            </Button>
          )}
        </div>
      </form>
    </section>
  )
}
