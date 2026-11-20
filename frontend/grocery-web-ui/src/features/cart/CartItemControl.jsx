import { useEffect, useId, useRef } from 'react'
import { useToast } from '../../shared/components/toast/useToast'
import { Button, Spinner } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { findLine, MAX_LINE_QUANTITY, targetItemId } from './api'
import { menuItemUnavailableReason, productUnavailableReason } from './availability'
import { cartErrorMessage } from './errors'
import { useAddToCart, useCart, useCartAccess, useCartItemBusy, useRemoveCartItem, useUpdateCartItem } from './hooks'
import { QuantityStepper } from './QuantityStepper'
import { StoreConflictDialog } from './StoreConflictDialog'

/** @typedef {import('react').Ref} Ref */
/** @typedef {import('../catalog/api').ProductCard} ProductCard */
/** @typedef {import('../restaurant-discovery/api').PublicMenuItem} PublicMenuItem */
/** @typedef {import('../restaurant-discovery/api').RestaurantDetail} RestaurantDetail */
/** @typedef {import('./api').CartItemTarget} CartItemTarget */
/** @typedef {import('./api').CartLine} CartLine */

/**
 * @typedef {object} CartLineStepperProps
 * @property {CartLine} line The live line from the cart.
 * @property {string | null} [unavailableReason] Blocks "+" (sold out, closed, …).
 * @property {string} [storeName]
 * @property {'sm' | 'md'} [size]
 * @property {'solid' | 'soft'} [tone] See `QuantityStepper`: `soft` in the rows of the cart.
 * @property {boolean} [block]
 * @property {string} [className]
 * @property {() => void} [onBeforeRemove] Called before "−" at quantity 1 removes the line.
 * @property {() => void} [onRemoved] Called once the line has been removed by "−".
 * @property {Ref<HTMLButtonElement>} [increaseRef]
 * @property {Ref<HTMLButtonElement>} [decreaseRef]
 */

/**
 * Stepper for a line already in the cart: PUT the new quantity; "−" at 1 removes the line. The quantity comes
 * from the `['cart']` cache when it holds the line (it is updated by every write at once, while a checkout
 * preview showing the same line is still being re-fetched).
 * @param {CartLineStepperProps} props
 */
export function CartLineStepper({
  line: shownLine,
  unavailableReason = null,
  storeName,
  size = 'sm',
  tone = 'solid',
  block = false,
  className,
  onBeforeRemove,
  onRemoved,
  increaseRef,
  decreaseRef,
}) {
  const cart = useCart()
  const line = findLine(cart.data, shownLine.type, shownLine.itemId) ?? shownLine
  const toast = useToast()
  const busy = useCartItemBusy(line.itemId)
  const update = useUpdateCartItem(line.itemId)
  const remove = useRemoveCartItem(line.itemId)

  const stockLimit = line.availableQuantity ?? MAX_LINE_QUANTITY
  const canIncrease = unavailableReason === null && line.quantity < Math.min(MAX_LINE_QUANTITY, stockLimit)
  const increaseHint =
    unavailableReason ??
    (line.quantity >= MAX_LINE_QUANTITY
      ? `You can add at most ${MAX_LINE_QUANTITY} of one item.`
      : line.availableQuantity !== null && line.quantity >= line.availableQuantity
        ? `Only ${line.availableQuantity} in stock.`
        : null)

  const onError = (error) => toast.error(cartErrorMessage(error, { itemName: line.name, storeName }))

  function decrease() {
    if (line.quantity <= 1) {
      onBeforeRemove?.()
      remove.mutate(undefined, { onSuccess: () => onRemoved?.(), onError })
    } else {
      update.mutate(line.quantity - 1, { onError })
    }
  }

  return (
    <QuantityStepper
      name={line.name}
      quantity={line.quantity}
      onDecrease={decrease}
      onIncrease={() => update.mutate(line.quantity + 1, { onError })}
      canIncrease={canIncrease}
      increaseHint={increaseHint}
      busy={busy}
      size={size}
      tone={tone}
      block={block}
      className={className}
      increaseRef={increaseRef}
      decreaseRef={decreaseRef}
    />
  )
}

/**
 * @typedef {object} CartItemControlProps
 * @property {CartItemTarget} target
 * @property {string} name
 * @property {string | null} unavailableReason Why it cannot be added now; null when it can.
 * @property {string} [storeName] Store or restaurant name for error messages.
 * @property {'sm' | 'md'} [size]
 * @property {boolean} [block] Stretch to the full width (product tiles).
 * @property {string} [className]
 */

/**
 * "Add" for customers and guests (guests are sent to the login page), then a "− n +" stepper once the item
 * is in the cart. Other roles see nothing.
 * @param {CartItemControlProps} props
 */
export function CartItemControl({ target, name, unavailableReason, storeName, size = 'sm', block = false, className }) {
  const access = useCartAccess()
  const cart = useCart()
  const itemId = targetItemId(target)
  const line = access === 'customer' ? findLine(cart.data, target.type, itemId) : undefined
  const adder = useAddToCart(target, name, { storeName })
  const busy = useCartItemBusy(itemId)
  const reasonId = useId()

  // Keep keyboard focus on the control when "Add" turns into the stepper and back.
  const addRef = useRef(null)
  const increaseRef = useRef(null)
  const decreaseRef = useRef(null)
  const pendingFocus = useRef(null)
  const inCart = line !== undefined
  useEffect(() => {
    if (pendingFocus.current === 'stepper' && inCart) {
      pendingFocus.current = null
      const increase = increaseRef.current
      ;(increase && !increase.disabled ? increase : decreaseRef.current)?.focus()
    } else if (pendingFocus.current === 'add' && !inCart) {
      pendingFocus.current = null
      addRef.current?.focus()
    }
  }, [inCart])

  if (access === 'unknown' || access === 'other') return null

  if (line) {
    return (
      <CartLineStepper
        line={line}
        unavailableReason={unavailableReason}
        storeName={storeName}
        size={size}
        block={block}
        className={className}
        onBeforeRemove={() => {
          pendingFocus.current = 'add'
        }}
        increaseRef={increaseRef}
        decreaseRef={decreaseRef}
      />
    )
  }

  const unavailable = unavailableReason !== null
  return (
    <>
      <Button
        ref={addRef}
        variant="secondary"
        size={size}
        disabled={unavailable || busy}
        aria-label={`Add ${name} to cart`}
        aria-describedby={unavailable ? reasonId : undefined}
        title={unavailableReason ?? undefined}
        onClick={() => {
          pendingFocus.current = 'stepper'
          adder.add(1)
        }}
        className={cn(
          'bg-emerald-50/60! font-semibold text-emerald-700! ring-emerald-500! hover:bg-emerald-100! disabled:bg-white! disabled:text-slate-400! disabled:ring-slate-200!',
          block && 'w-full',
          size === 'md' && 'min-w-32',
          className,
        )}
      >
        {busy && <Spinner size="sm" />}
        Add
      </Button>
      {unavailable && (
        <span id={reasonId} className="sr-only">
          {unavailableReason}
        </span>
      )}
      <StoreConflictDialog {...adder.conflictDialog} />
    </>
  )
}

/**
 * @typedef {object} ProductCartControlProps
 * @property {Pick<ProductCard, 'id' | 'name' | 'price' | 'inStock' | 'vendor'>} product
 * @property {'sm' | 'md'} [size]
 * @property {boolean} [block]
 */

/**
 * Add / stepper for a grocery product (tiles and the product page).
 * @param {ProductCartControlProps} props
 */
export function ProductCartControl({ product, size, block }) {
  return (
    <CartItemControl
      target={{ type: 'PRODUCT', productId: product.id }}
      name={product.name}
      unavailableReason={productUnavailableReason(product)}
      storeName={product.vendor.name}
      size={size}
      block={block}
    />
  )
}

/**
 * @typedef {object} MenuItemCartControlProps
 * @property {Pick<RestaurantDetail, 'id' | 'name' | 'openNow' | 'acceptingOrders'>} restaurant
 * @property {PublicMenuItem} item
 */

/**
 * Add / stepper for a dish on a restaurant page.
 * @param {MenuItemCartControlProps} props
 */
export function MenuItemCartControl({ restaurant, item }) {
  return (
    <CartItemControl
      target={{ type: 'MENU_ITEM', restaurantId: restaurant.id, menuItemId: item.id }}
      name={item.name}
      unavailableReason={menuItemUnavailableReason(restaurant, item)}
      storeName={restaurant.name}
      className="min-w-20"
    />
  )
}
