import type { Ref } from 'react'
import { cn } from '../../shared/utils/cn'

interface QuantityStepperProps {
  /** Product or dish name, used in the button labels ("Increase quantity of Tomato"). */
  name: string
  quantity: number
  onDecrease: () => void
  onIncrease: () => void
  /** False at the line limit, the available stock, or when the item cannot be ordered now. */
  canIncrease: boolean
  /** Why "+" is disabled (read out as the button's description). */
  increaseHint?: string | null
  /** A request for this item is running: both buttons wait. */
  busy?: boolean
  size?: 'sm' | 'md'
  /** `solid` (product tiles, menus) or `soft`: white with a green outline, calmer in a list of cart rows. */
  tone?: 'solid' | 'soft'
  /** Stretch to the full width (product tiles). */
  block?: boolean
  className?: string
  increaseRef?: Ref<HTMLButtonElement>
  decreaseRef?: Ref<HTMLButtonElement>
}

const sizes = {
  sm: { box: 'h-8', button: 'w-8 text-base', value: 'min-w-7 text-sm' },
  md: { box: 'h-10', button: 'w-10 text-lg', value: 'min-w-9 text-base' },
} as const

const tones = {
  solid: {
    box: 'bg-emerald-600 text-white shadow-sm shadow-emerald-600/25',
    button: 'hover:bg-emerald-700 disabled:hover:bg-transparent',
    value: '',
  },
  soft: {
    box: 'bg-white text-emerald-700 shadow-xs ring-1 ring-emerald-600/25 ring-inset',
    button: 'hover:bg-emerald-50 disabled:hover:bg-transparent',
    value: 'text-slate-900',
  },
} as const

/** "− n +" for a line in the cart. "−" at 1 removes the line. */
export function QuantityStepper({
  name,
  quantity,
  onDecrease,
  onIncrease,
  canIncrease,
  increaseHint,
  busy = false,
  size = 'sm',
  tone = 'solid',
  block = false,
  className,
  increaseRef,
  decreaseRef,
}: QuantityStepperProps) {
  const styles = sizes[size]
  const look = tones[tone]
  const buttonClass = cn(
    'flex h-full items-center justify-center font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 disabled:cursor-not-allowed disabled:opacity-50',
    look.button,
    styles.button,
  )
  return (
    <div
      role="group"
      aria-label={`Quantity of ${name}`}
      aria-busy={busy || undefined}
      className={cn(
        'items-stretch overflow-hidden rounded-xl',
        look.box,
        block ? 'flex w-full justify-between' : 'inline-flex',
        styles.box,
        className,
      )}
    >
      <button
        ref={decreaseRef}
        type="button"
        onClick={onDecrease}
        disabled={busy}
        aria-label={quantity <= 1 ? `Remove ${name} from cart` : `Decrease quantity of ${name}`}
        className={buttonClass}
      >
        <span aria-hidden="true">−</span>
      </button>
      <span className={cn('flex items-center justify-center px-1 font-semibold tabular-nums', styles.value, look.value, busy && 'opacity-70')}>
        {quantity}
        <span className="sr-only"> in cart</span>
      </span>
      <button
        ref={increaseRef}
        type="button"
        onClick={onIncrease}
        disabled={busy || !canIncrease}
        aria-label={`Increase quantity of ${name}`}
        title={!canIncrease && increaseHint ? increaseHint : undefined}
        className={buttonClass}
      >
        <span aria-hidden="true">+</span>
      </button>
    </div>
  )
}
