import { keepPreviousData, useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { hasErrorCode, problemString } from '../../shared/api/problemDetail'
import { locationToPath } from '../../shared/auth/redirect'
import { useAuth } from '../../shared/auth/useAuth'
import { useToast } from '../../shared/components/toast/useToast'
import {
  addCartItem,
  applyCartCoupon,
  CART_STORE_CONFLICT,
  cartKeys,
  clearCart,
  COUPON_NOT_APPLICABLE,
  emptyCart,
  fetchCart,
  fetchCheckoutPreview,
  removeCartCoupon,
  removeCartItem,
  targetItemId,
  updateCartItem,
} from './api'
import { cartErrorMessage } from './errors'

/** @typedef {import('@tanstack/react-query').QueryClient} QueryClient */
/** @typedef {import('./api').CartItemTarget} CartItemTarget */
/** @typedef {import('./api').CartView} CartView */

/**
 * Who may use the cart: signed-in customers; guests are sent to the login page; other roles (vendors,
 * admins, …) never see cart controls. `unknown` while the session is being restored.
 * @typedef {'customer' | 'guest' | 'other' | 'unknown'} CartAccess
 */

/** @returns {CartAccess} */
export function useCartAccess() {
  const { status, area } = useAuth()
  if (status === 'initializing') return 'unknown'
  if (status === 'anonymous') return 'guest'
  if (status === 'authenticated' && area === 'CUSTOMER') return 'customer'
  return 'other'
}

/**
 * The customer's cart — the single `['cart']` query every cart control reads. Prices and stock are
 * re-checked by the server on every read, so the drawer and the cart page refetch whenever they open.
 * @param {{ refetchOnMount?: boolean | 'always' }} [options={}]
 */
export function useCart(options = {}) {
  const access = useCartAccess()
  return useQuery({
    queryKey: cartKeys.cart(),
    queryFn: fetchCart,
    enabled: access === 'customer',
    refetchOnMount: options.refetchOnMount ?? true,
  })
}

/**
 * Checkout preview for one address (null = the customer's default address).
 * @param {string | null} addressId
 * @param {{ enabled?: boolean }} [options={}]
 */
export function useCheckoutPreview(addressId, options = {}) {
  const access = useCartAccess()
  return useQuery({
    queryKey: cartKeys.preview(addressId),
    queryFn: () => fetchCheckoutPreview(addressId),
    enabled: access === 'customer' && (options.enabled ?? true),
    // Switching address keeps the last preview on screen (dimmed) until the new one arrives.
    placeholderData: keepPreviousData,
    staleTime: 0,
  })
}

/**
 * Every cart write answers with the whole CartView: it becomes the cache, and previews are re-priced.
 * @param {QueryClient} queryClient
 * @param {CartView} view
 */
function applyCartView(queryClient, view) {
  queryClient.setQueryData(cartKeys.cart(), view)
  void queryClient.invalidateQueries({ queryKey: cartKeys.previews() })
}

/** All cart writes run one at a time (mutation scope), so their answers can never land out of order. */
const CART_SCOPE = { id: 'cart' }

/**
 * @template TVariables
 * @param {string} itemId
 * @param {(variables: TVariables) => Promise<CartView>} mutationFn
 */
function useCartWrite(itemId, mutationFn) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: cartKeys.itemWrite(itemId),
    scope: CART_SCOPE,
    mutationFn,
    // A cart read still in flight must not overwrite the fresher answer of this write.
    onMutate: () => queryClient.cancelQueries({ queryKey: cartKeys.cart(), exact: true }),
    onSuccess: (view) => applyCartView(queryClient, view),
    // Something changed on the server (stock, store, another tab): show the cart as it is now.
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: cartKeys.all })
    },
  })
}

/** @param {string} itemId */
export function useAddCartItem(itemId) {
  return useCartWrite(itemId, (body) => addCartItem(body))
}

/**
 * 0 removes the line.
 * @param {string} itemId
 */
export function useUpdateCartItem(itemId) {
  return useCartWrite(itemId, (quantity) => updateCartItem(itemId, quantity))
}

/** @param {string} itemId */
export function useRemoveCartItem(itemId) {
  return useCartWrite(itemId, () => removeCartItem(itemId))
}

export function useClearCart() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: cartKeys.clear(),
    scope: CART_SCOPE,
    mutationFn: clearCart,
    onMutate: () => queryClient.cancelQueries({ queryKey: cartKeys.cart(), exact: true }),
    onSuccess: () => {
      const currency = queryClient.getQueryData(cartKeys.cart())?.currency
      applyCartView(queryClient, emptyCart(currency))
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: cartKeys.all })
    },
  })
}

/**
 * Phase 12: apply a coupon code (`PUT /api/cart/coupon`) or remove it. Like every cart write the answer becomes the
 * cart and the checkout preview is re-priced (the preview carries the coupon, its discount and its validity).
 * @template TVariables
 * @param {(variables: TVariables) => Promise<CartView>} mutationFn
 */
function useCouponWrite(mutationFn) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: cartKeys.couponWrite(),
    scope: CART_SCOPE,
    mutationFn,
    onMutate: () => queryClient.cancelQueries({ queryKey: cartKeys.cart(), exact: true }),
    onSuccess: (view) => applyCartView(queryClient, view),
    // A refused code changes nothing; anything else may mean the cart moved on — re-read it.
    onError: (error) => {
      if (!hasErrorCode(error, COUPON_NOT_APPLICABLE)) void queryClient.invalidateQueries({ queryKey: cartKeys.all })
    },
  })
}

export function useApplyCoupon() {
  return useCouponWrite((code) => applyCartCoupon(code))
}

export function useRemoveCoupon() {
  return useCouponWrite(() => removeCartCoupon())
}

/**
 * True while a write for this item (from any control on the page) or a "clear cart" is running.
 * @param {string} itemId
 * @returns {boolean}
 */
export function useCartItemBusy(itemId) {
  const itemWrites = useIsMutating({ mutationKey: cartKeys.itemWrite(itemId) })
  const clearing = useIsMutating({ mutationKey: cartKeys.clear() })
  return itemWrites + clearing > 0
}

/**
 * True while any cart write is running.
 * @returns {boolean}
 */
export function useCartBusy() {
  return useIsMutating({ mutationKey: ['cart-write'] }) > 0
}

/**
 * Props for `StoreConflictDialog`.
 * @typedef {object} StoreConflictState
 * @property {boolean} open
 * @property {string} currentStoreName Store the cart currently belongs to.
 * @property {string} itemName
 * @property {boolean} pending
 * @property {() => void} onConfirm
 * @property {() => void} onClose
 */

/**
 * "Add" for one product or dish. Guests go to the login page and come back here afterwards. A cart from
 * another store (`409 CART_STORE_CONFLICT`) asks "Start a new cart?" and repeats the request with
 * `replaceCart: true`; every other refusal becomes a friendly toast.
 * @param {CartItemTarget} target
 * @param {string} itemName
 * @param {{ storeName?: string }} [context={}]
 */
export function useAddToCart(target, itemName, context = {}) {
  const access = useCartAccess()
  const navigate = useNavigate()
  const location = useLocation()
  const toast = useToast()
  const queryClient = useQueryClient()
  const mutation = useAddCartItem(targetItemId(target))
  const [conflict, setConflict] = useState(null)

  function add(quantity = 1, replaceCart = false) {
    if (access === 'guest') {
      const state = { from: locationToPath(location) }
      void navigate('/login', { state })
      return
    }
    if (access !== 'customer') return
    const body = replaceCart ? { ...target, quantity, replaceCart: true } : { ...target, quantity }
    mutation.mutate(body, {
      onSuccess: (view) => {
        setConflict(null)
        if (replaceCart) toast.success(`Started a new cart from ${view.store?.name ?? 'this store'}.`)
      },
      onError: (error) => {
        if (!replaceCart && hasErrorCode(error, CART_STORE_CONFLICT)) {
          const cached = queryClient.getQueryData(cartKeys.cart())
          setConflict({
            storeName: problemString(error, 'currentStoreName') ?? cached?.store?.name ?? 'another store',
            quantity,
          })
          return
        }
        setConflict(null)
        toast.error(cartErrorMessage(error, { itemName, storeName: context.storeName }))
      },
    })
  }

  const conflictDialog = {
    open: conflict !== null,
    currentStoreName: conflict?.storeName ?? '',
    itemName,
    pending: mutation.isPending,
    onConfirm: () => add(conflict?.quantity ?? 1, true),
    onClose: () => setConflict(null),
  }

  return { add, isPending: mutation.isPending, conflictDialog }
}
