import { cn } from '../../shared/utils/cn'

/** @typedef {import('react').Ref} Ref */

/**
 * @typedef {object} QuantityStepperProps
 * @property {string} name Product or dish name, used in the button labels ("Increase quantity of Tomato").
 * @property {number} quantity
 * @property {() => void} onDecrease
 * @property {() => void} onIncrease
 * @property {boolean} canIncrease False at the line limit, the available stock, or when the item cannot be ordered now.
 * @property {string | null} [increaseHint] Why "+" is disabled (read out as the button's description).
 * @property {boolean} [busy] A request for this item is running: both buttons wait.
 * @property {'sm' | 'md'} [size]
 * @property {'solid' | 'soft'} [tone] `solid` (product tiles, menus) or `soft`: white with a green outline, calmer in a list of cart rows.
 * @property {boolean} [block] Stretch to the full width (product tiles).
 * @property {string} [className]
 * @property {Ref<HTMLButtonElement>} [increaseRef]
 * @property {Ref<HTMLButtonElement>} [decreaseRef]
 */

const sizes = {
  sm: { box: 'h-8', button: 'w-8 text-base', value: 'min-w-7 text-sm' },
  md: { box: 'h-10', button: 'w-10 text-lg', value: 'min-w-9 text-base' },
}

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
}

/**
 * "− n +" for a line in the cart. "−" at 1 removes the line.
 * @param {QuantityStepperProps} props
 */
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
}) {
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
      <span
        className={cn(
          'flex items-center justify-center px-1 font-semibold tabular-nums',
          styles.value,
          look.value,
          busy && 'opacity-70',
        )}
      >
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
