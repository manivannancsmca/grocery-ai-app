import { ApiError, errorMessage, hasErrorCode, problemString } from '../../shared/api/problemDetail'
import { isStoreOrderStatus, STORE_ORDER_ERRORS, type StoreKind, type StoreOrderView } from './api'
import { storeOrderStatusLabel } from './labels'

/**
 * The store order's current status carried by STORE_ORDER_NOT_PENDING / INVALID_STORE_ORDER_TRANSITION. The
 * contract names the property `status`, which collides with ProblemDetail's own numeric `status` (409): accept
 * it wherever it lands as a store status string (top level, under `properties`), or as `currentStatus`.
 */
export function conflictStatus(error: unknown): string | null {
  if (!(error instanceof ApiError) || !error.problem) return null
  const nested = error.problem.properties
  const candidates: unknown[] = [
    problemString(error, 'currentStatus'),
    error.problem.status,
    typeof nested === 'object' && nested !== null ? (nested as Record<string, unknown>).status : undefined,
  ]
  const status = candidates.find(isStoreOrderStatus)
  return status ?? null
}

export interface ActionErrorToast {
  tone: 'warning' | 'error'
  message: string
  description?: string
  /** The order moved on meanwhile: the action cannot be retried, the refreshed board shows its real state. */
  conflict: boolean
}

const REFRESHED = 'The board has been refreshed.'

function nowStatus(kind: StoreKind, error: unknown): string {
  const status = conflictStatus(error)
  return status ? `It is now ${storeOrderStatusLabel(kind, status).toLowerCase()}. ` : ''
}

/** A failed accept / reject / preparing / ready in words for a toast (each 409 of the contract has its own). */
export function storeOrderActionError(kind: StoreKind, order: Pick<StoreOrderView, 'orderNumber'>, error: unknown): ActionErrorToast {
  const number = order.orderNumber
  if (hasErrorCode(error, STORE_ORDER_ERRORS.notPending)) {
    return {
      tone: 'warning',
      message: `Order ${number} is no longer waiting for you`,
      description: `${nowStatus(kind, error)}${REFRESHED}`,
      conflict: true,
    }
  }
  if (hasErrorCode(error, STORE_ORDER_ERRORS.acceptanceExpired)) {
    return {
      tone: 'warning',
      message: `Too late to accept order ${number}`,
      description: `The time to accept ran out, so the order is rejected automatically and the customer refunded. ${REFRESHED}`,
      conflict: true,
    }
  }
  if (hasErrorCode(error, STORE_ORDER_ERRORS.invalidTransition)) {
    return {
      tone: 'warning',
      message: `Order ${number} has already moved on`,
      description: `${nowStatus(kind, error)}${REFRESHED}`,
      conflict: true,
    }
  }
  if (hasErrorCode(error, STORE_ORDER_ERRORS.notFound)) {
    return { tone: 'warning', message: `Order ${number} is no longer on your board`, description: REFRESHED, conflict: true }
  }
  return {
    tone: 'error',
    message: `Could not update order ${number}`,
    description: error instanceof ApiError && error.correlationId ? `${errorMessage(error)} (Ref: ${error.correlationId})` : errorMessage(error),
    conflict: false,
  }
}
