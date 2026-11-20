import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../../shared/api/problemDetail'
import { cn } from '../../shared/utils/cn'
import { useAddresses } from '../customer/hooks'
import { cartErrorMessage } from './errors'
import { itemsLabel } from './format'
import { useCart, useCheckoutPreview } from './hooks'
import { IllustratedError } from './IllustratedError'
import { EmptyCart, LoadingCart, PageTitle } from './CartPageStates'
import { CartWithItems } from './CartWithItems'
import { PAGE } from './cartPageStyles'

/**
 * `/cart` — the whole cart on one page: the store with its items (live prices and stock, issues on the rows), and an
 * order summary beside them on wide screens (address, nudges, coupon, the bill from the checkout preview, total and
 * "Proceed to checkout"); on narrower screens the summary follows the items and a bar with the total and the button
 * sticks to the bottom.
 */
export function CartPageView() {
  // Prices and stock are re-checked live on every read, so the cart refreshes whenever the page opens.
  const cart = useCart({ refetchOnMount: 'always' })
  const view = cart.data
  const hasItems = Boolean(view?.store && view.lines.length > 0)
  const addresses = useAddresses()
  const [chosenId, setChosenId] = useState(null)
  const list = addresses.data ?? []
  // The default address is preselected; the customer's choice wins while it still exists.
  const selected =
    list.find((address) => address.id === chosenId) ?? list.find((address) => address.isDefault) ?? list[0] ?? null
  const preview = useCheckoutPreview(selected?.id ?? null, { enabled: hasItems && !addresses.isPending })

  // After a line disappears (remove, "−" at 1, clear), keep keyboard focus inside the cart.
  const rootRef = useRef(null)
  const [focusRequest, setFocusRequest] = useState(0)
  useEffect(() => {
    if (focusRequest > 0) rootRef.current?.focus()
  }, [focusRequest])
  const keepFocus = () => setFocusRequest((value) => value + 1)

  let description = 'Prices and stock are checked live every time you open your cart.'
  let body
  if (cart.isPending) {
    body = <LoadingCart />
  } else if (cart.isError) {
    body = (
      <IllustratedError
        title="Could not load your cart"
        message={cartErrorMessage(cart.error)}
        correlationId={cart.error instanceof ApiError ? cart.error.correlationId : null}
        onRetry={() => void cart.refetch()}
      />
    )
  } else if (!hasItems) {
    description = 'Pick a store or a restaurant to start an order.'
    body = <EmptyCart />
  } else {
    const data = cart.data
    const store = data.store
    description = `${itemsLabel(data.itemCount)} from ${store.name}`
    body = (
      <CartWithItems
        cart={data}
        store={store}
        preview={preview.data}
        previewLoading={preview.isPending}
        previewUpdating={preview.isPlaceholderData || (preview.isFetching && !preview.isPending)}
        previewError={preview.isError && !preview.data ? preview.error : null}
        onRetryPreview={() => void preview.refetch()}
        onRecheck={() => {
          void cart.refetch()
          void preview.refetch()
        }}
        rechecking={cart.isFetching}
        addresses={{
          list,
          loading: addresses.isPending,
          failed: addresses.isError,
          retry: () => void addresses.refetch(),
          selected,
          choose: setChosenId,
        }}
        onLineRemoved={keepFocus}
      />
    )
  }

  return (
    <div
      ref={rootRef}
      tabIndex={-1}
      aria-busy={cart.isFetching || undefined}
      className={cn(PAGE, 'focus:outline-none')}
    >
      <PageTitle description={description} />
      <section aria-label="Cart" className="animate-fade-up">
        {body}
      </section>
    </div>
  )
}
