import { cn } from '../utils/cn'
import { FOOD_TYPE_LABELS, FOOD_TYPE_SHORT_LABELS, type FoodType } from '../utils/foodType'

const STYLES: Record<FoodType, { box: string; fill: string }> = {
  VEG: { box: 'border-green-700', fill: 'fill-green-700' },
  NON_VEG: { box: 'border-red-800', fill: 'fill-red-800' },
  EGG: { box: 'border-amber-500', fill: 'fill-amber-500' },
}

interface FoodTypeMarkProps {
  foodType: FoodType | null | undefined
  size?: 'sm' | 'md'
  /** Shows the short label ("Veg", "Non-veg", "Egg") next to the mark. */
  showLabel?: boolean
  className?: string
}

/**
 * The Indian food-type symbol: a square outline with a filled circle (veg, green), a filled triangle
 * (non-veg, brown-red) or a yellow circle (egg). Screen readers hear "Vegetarian" etc.
 */
export function FoodTypeMark({ foodType, size = 'sm', showLabel = false, className }: FoodTypeMarkProps) {
  if (!foodType) return null
  const style = STYLES[foodType]
  const label = FOOD_TYPE_LABELS[foodType]
  const mark = (
    <span
      role={showLabel ? undefined : 'img'}
      aria-label={showLabel ? undefined : label}
      aria-hidden={showLabel ? true : undefined}
      title={label}
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-[3px] border-2 bg-white',
        size === 'sm' ? 'size-4' : 'size-5',
        style.box,
        !showLabel && className,
      )}
    >
      <svg viewBox="0 0 10 10" className={cn(size === 'sm' ? 'size-2' : 'size-2.5', style.fill)} aria-hidden="true">
        {foodType === 'NON_VEG' ? <path d="M5 1 9.5 9h-9z" /> : <circle cx="5" cy="5" r="4.2" />}
      </svg>
    </span>
  )
  if (!showLabel) return mark
  return (
    <span className={cn('inline-flex items-center gap-1.5 text-xs font-medium text-slate-700', className)}>
      {mark}
      <span>{FOOD_TYPE_SHORT_LABELS[foodType]}</span>
    </span>
  )
}
