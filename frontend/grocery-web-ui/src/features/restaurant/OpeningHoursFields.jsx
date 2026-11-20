import { useWatch } from 'react-hook-form'
import { Button, Checkbox, Input } from '../../shared/components/ui'
import { fieldErrorMessage } from '../../shared/forms/serverErrors'
import { humanizeToken } from '../../shared/utils/format'
import { DAYS_OF_WEEK } from './api'

/** @typedef {import('react-hook-form').UseFormReturn} UseFormReturn */
/** @typedef {import('./schema').RestaurantFormValues} RestaurantFormValues */

/**
 * @typedef {object} OpeningHoursFieldsProps
 * @property {UseFormReturn<RestaurantFormValues>} form
 * @property {boolean} [disabled]
 */

/**
 * One row per weekday: open toggle plus opening and closing time (overnight hours are allowed).
 * @param {OpeningHoursFieldsProps} props
 */
export function OpeningHoursFields({ form, disabled }) {
  const {
    register,
    setValue,
    getValues,
    control,
    formState: { errors },
  } = form
  const rows = useWatch({ control, name: 'openingHours' })
  const listError = fieldErrorMessage(errors.openingHours?.root) ?? errors.openingHours?.message

  function copyFirstOpenDayToAll() {
    const source = getValues('openingHours').find((row) => row.open)
    if (!source) return
    DAYS_OF_WEEK.forEach((_, index) => {
      setValue(`openingHours.${index}.open`, true, { shouldDirty: true })
      setValue(`openingHours.${index}.opensAt`, source.opensAt, { shouldDirty: true })
      setValue(`openingHours.${index}.closesAt`, source.closesAt, { shouldDirty: true, shouldValidate: true })
    })
  }

  return (
    <div className="space-y-3">
      <ul className="divide-y divide-slate-100 rounded-lg border border-slate-200">
        {DAYS_OF_WEEK.map((day, index) => {
          const open = rows[index]?.open ?? false
          const rowErrors = errors.openingHours?.[index]
          const dayLabel = humanizeToken(day)
          const rowError = rowErrors?.opensAt?.message ?? rowErrors?.closesAt?.message
          return (
            <li key={day} className="flex flex-col gap-2 px-3 py-2.5 sm:flex-row sm:items-center sm:gap-4">
              <label className="flex w-36 shrink-0 items-center gap-2 text-sm font-medium text-slate-700">
                <Checkbox disabled={disabled} {...register(`openingHours.${index}.open`)} />
                {dayLabel}
              </label>
              {open ? (
                <div className="flex flex-1 flex-col gap-1">
                  <div className="flex items-center gap-2">
                    <Input
                      type="time"
                      aria-label={`${dayLabel} opens at`}
                      className="w-32"
                      disabled={disabled}
                      invalid={Boolean(rowErrors?.opensAt)}
                      {...register(`openingHours.${index}.opensAt`)}
                    />
                    <span className="text-sm text-slate-400">to</span>
                    <Input
                      type="time"
                      aria-label={`${dayLabel} closes at`}
                      className="w-32"
                      disabled={disabled}
                      invalid={Boolean(rowErrors?.closesAt)}
                      {...register(`openingHours.${index}.closesAt`)}
                    />
                  </div>
                  {rowError && <p className="text-xs font-medium text-red-600">{rowError}</p>}
                </div>
              ) : (
                <span className="text-sm text-slate-400">Closed</span>
              )}
            </li>
          )
        })}
      </ul>
      {listError && <p className="text-xs font-medium text-red-600">{listError}</p>}
      {!disabled && (
        <Button variant="ghost" size="sm" onClick={copyFirstOpenDayToAll} disabled={!rows.some((row) => row.open)}>
          Use the first open day's hours for every day
        </Button>
      )}
    </div>
  )
}
