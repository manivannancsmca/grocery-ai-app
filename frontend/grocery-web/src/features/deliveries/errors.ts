import { ApiError, errorMessage, hasErrorCode, problemString } from '../../shared/api/problemDetail'
import { DELIVERY_ERRORS, isDeliveryStatus } from './api'
import { deliveryStatusLabel } from './labels'

/** A failed partner action in words for a toast. */
export interface DeliveryErrorToast {
  tone: 'warning' | 'error'
  message: string
  description?: string
}

function withReference(error: unknown): string {
  return error instanceof ApiError && error.correlationId ? `${errorMessage(error)} (Ref: ${error.correlationId})` : errorMessage(error)
}

/**
 * The status a 409 carries as `currentStatus` (Phase 9 conventions). Spring may also write it under
 * `properties`; ProblemDetail's own `status` is the HTTP code and never read here.
 */
export function currentStatus(error: unknown): string | null {
  return problemString(error, 'currentStatus')
}

/** Online / offline failed. */
export function availabilityError(error: unknown): DeliveryErrorToast {
  if (hasErrorCode(error, DELIVERY_ERRORS.partnerNotActive)) {
    return {
      tone: 'warning',
      message: 'Your account is waiting for approval',
      description: 'You can go online once an admin approves your partner profile.',
    }
  }
  if (hasErrorCode(error, DELIVERY_ERRORS.activeDelivery)) {
    return {
      tone: 'warning',
      message: 'Finish your delivery before going offline',
      description: 'You have an active delivery. Deliver it (or mark it as not delivered) first, then go offline.',
    }
  }
  return { tone: 'error', message: 'Could not change your availability', description: withReference(error) }
}

/** Accept / reject of an offer failed. */
export function offerError(storeName: string, error: unknown): DeliveryErrorToast {
  if (hasErrorCode(error, DELIVERY_ERRORS.offerNotAvailable)) {
    return currentStatus(error) === 'EXPIRED'
      ? { tone: 'warning', message: 'This offer has expired', description: 'Your offers have been refreshed.' }
      : { tone: 'warning', message: 'Another partner took it', description: `The delivery from ${storeName} is no longer available. Your offers have been refreshed.` }
  }
  if (hasErrorCode(error, DELIVERY_ERRORS.offerNotFound)) {
    return { tone: 'warning', message: 'This offer is no longer yours', description: 'Your offers have been refreshed.' }
  }
  if (hasErrorCode(error, DELIVERY_ERRORS.partnerNotActive)) return availabilityError(error)
  return { tone: 'error', message: 'Could not answer the offer', description: withReference(error) }
}

/** Picked up / delivered / couldn't deliver failed. */
export function deliveryActionError(orderNumber: string, error: unknown): DeliveryErrorToast {
  if (hasErrorCode(error, DELIVERY_ERRORS.orderNotReady)) {
    return {
      tone: 'warning',
      message: `Order ${orderNumber} is not ready yet`,
      description: 'The store has not marked it ready for pickup. Wait until it shows “Order ready”, then try again.',
    }
  }
  if (hasErrorCode(error, DELIVERY_ERRORS.invalidTransition)) {
    const status = currentStatus(error)
    return {
      tone: 'warning',
      message: `Order ${orderNumber} has already moved on`,
      description: `${isDeliveryStatus(status) ? `It is now ${deliveryStatusLabel(status).toLowerCase()}. ` : ''}Your delivery has been refreshed.`,
    }
  }
  if (hasErrorCode(error, DELIVERY_ERRORS.deliveryNotFound)) {
    return { tone: 'warning', message: `Order ${orderNumber} is no longer assigned to you`, description: 'Your delivery has been refreshed.' }
  }
  return { tone: 'error', message: `Could not update order ${orderNumber}`, description: withReference(error) }
}
