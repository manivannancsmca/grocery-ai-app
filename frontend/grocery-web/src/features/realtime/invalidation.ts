import type { QueryKey } from '@tanstack/react-query'
import type { AccountKind, ApprovalKind, NotificationView, Signal } from '../../shared/realtime/messages'
import { adminDeliveryKeys } from '../admin-deliveries/api'
import { adminOrderKeys } from '../admin-orders/api'
import { adminPaymentKeys } from '../admin-payments/api'
import { adminPriceKeys } from '../admin-pricing/api'
import { approvalKeys } from '../ai/api'
import { keysChangedBy } from '../ai/labels'
import { cartKeys } from '../cart/api'
import { catalogKeys } from '../catalog/api'
import { customerKeys } from '../customer/api'
import { partnerKeys } from '../delivery/api'
import { deliveryKeys } from '../deliveries/api'
import { menuKeys } from '../menu/api'
import { trackingKeys } from '../order-tracking/api'
import { orderKeys } from '../orders/api'
import { paymentKeys } from '../payments/api'
import { restaurantKeys } from '../restaurant/api'
import { discoveryKeys } from '../restaurant-discovery/api'
import { returnKeys } from '../returns/api'
import { reviewKeys } from '../reviews/api'
import { storeOrderKeys } from '../store-orders/api'
import { storeReportKeys } from '../store-reports/api'
import { returnKeys as storeReturnKeys } from '../store-returns/api'
import { storeReviewKeys } from '../store-reviews/api'
import { vendorKeys } from '../vendor/api'
import { inventoryKeys } from '../vendor-inventory/api'
import { vendorPriceKeys } from '../vendor-pricing/api'

/**
 * The one place that maps a live signal or notification to the TanStack Query keys it makes stale
 * (Phase 7 contract §2 "Client refetches"). Keys are prefixes: active queries under them refetch now,
 * inactive ones on their next use.
 */
export interface InvalidationPlan {
  keys: QueryKey[]
  /** RESYNC: every cached query (inbox included). */
  everything?: boolean
  /** Renew the access token silently first: an approval adds the `vendor_id` / `restaurant_id` claim. */
  refreshToken?: boolean
}

const NOTHING: InvalidationPlan = { keys: [] }

/** The order, its payment and every order list — or all orders when the id is missing. */
function orderPlan(orderId: string | undefined): InvalidationPlan {
  return { keys: orderId ? [orderKeys.detail(orderId), orderKeys.lists(), paymentKeys.forOrder(orderId)] : [orderKeys.all, paymentKeys.all] }
}

/** Phase 12: a return event arrives as ORDER_UPDATED with a returnId — the customer's return lists refresh too. */
function orderWithReturnsPlan(orderId: string | undefined): InvalidationPlan {
  const plan = orderPlan(orderId)
  return { ...plan, keys: [...plan.keys, ...(orderId ? [returnKeys.forOrder(orderId)] : []), returnKeys.mine()] }
}

/** Admin order and payment monitors (lists and drawers) and the dashboard's order stats. */
function adminOrdersPlan(orderId: string | undefined): InvalidationPlan {
  return {
    keys: [adminOrderKeys.all, adminOrderKeys.allStats(), adminPaymentKeys.all, ...(orderId ? [paymentKeys.forOrder(orderId)] : [])],
  }
}

/** Stock or price of a product: its page, every product search (search page, storefront, store pages) and the cart. */
function productPlan(productId: string | undefined): InvalidationPlan {
  return { keys: [productId ? catalogKeys.product(productId) : catalogKeys.all, catalogKeys.searches(), cartKeys.all] }
}

/** Restaurant pages and restaurant search, and the cart (a restaurant cart can become unorderable). */
function restaurantPlan(restaurantId: string | undefined): InvalidationPlan {
  return { keys: [restaurantId ? discoveryKeys.detail(restaurantId) : discoveryKeys.all, discoveryKeys.searches(), cartKeys.all] }
}

/**
 * Phase 8: the signed-in store's order board (vendor or restaurant — only one of them is ever cached) and its
 * sales reports, whose counts change with every store order event.
 */
export const STORE_ORDERS_PLAN: InvalidationPlan = { keys: [storeOrderKeys.all, storeReportKeys.all] }

/** Phase 9 (partner): the pending offers and the status (its pending-offer count). */
export const PARTNER_OFFERS_PLAN: InvalidationPlan = { keys: [deliveryKeys.offers(), deliveryKeys.status()] }

/**
 * Phase 9 (partner): the active delivery changed, or was taken away (cancelled, reassigned): it, the status,
 * the offers (an assignment closes the others) and the history.
 */
export const PARTNER_DELIVERY_PLAN: InvalidationPlan = {
  keys: [deliveryKeys.active(), deliveryKeys.status(), deliveryKeys.offers(), deliveryKeys.history()],
}

/** Phase 9 (customer): the order (as for any order event) and its live tracking. */
function trackedOrderPlan(orderId: string | undefined): InvalidationPlan {
  return { keys: [...orderPlan(orderId).keys, orderId ? trackingKeys.detail(orderId) : trackingKeys.all] }
}

/**
 * Phase 9 (admins): the delivery monitor (lists, drawers, online partners) and the order's admin drawer, which
 * shows the partner (order-service records an assignment without an order event of its own).
 */
function adminDeliveriesPlan(orderId: string | undefined): InvalidationPlan {
  return { keys: [adminDeliveryKeys.all, ...(orderId ? [adminOrderKeys.detail(orderId)] : [])] }
}

/**
 * Phase 11: the approval lists (to decide, mine, the customer's page) and that request's card — in the chat or a
 * drawer — or every request when the id is missing.
 */
function approvalPlan(approvalId: string | undefined): InvalidationPlan {
  return { keys: [approvalKeys.lists(), approvalId ? approvalKeys.detail(approvalId) : approvalKeys.details()] }
}

/**
 * Phase 12 (customer): their review was not published or the store replied — their reviews, what is left to review and
 * the reviewed target's public reviews (every target's when `data` does not name it).
 */
function customerReviewPlan(notification: NotificationView): InvalidationPlan {
  const targetType = dataText(notification, 'targetType')
  const targetId = dataText(notification, 'targetId')
  return {
    keys: [reviewKeys.mine(), reviewKeys.eligibleAll(), targetType && targetId ? reviewKeys.target(targetType, targetId) : reviewKeys.publicAll()],
  }
}

/** Phase 12 (customer): a return was decided — their returns and the order (its `returns`, `returnable`, refund). */
function customerReturnPlan(orderId: string | undefined): InvalidationPlan {
  return { keys: [returnKeys.all, ...orderPlan(orderId).keys] }
}

/** A store notification links into the store area (`/vendor…`, `/restaurant…`); a customer's to `/orders/…`. */
function isStoreLink(link: string | null): boolean {
  return link !== null && /^\/(vendor|restaurant)(\/|$)/.test(link)
}

/** The admin queue (and directory) of each approval kind. */
export const APPROVAL_QUEUE_KEYS: Record<ApprovalKind, QueryKey> = {
  VENDOR: vendorKeys.admin,
  RESTAURANT: restaurantKeys.admin,
  DELIVERY_PARTNER: partnerKeys.admin,
  PRICE_CHANGE: adminPriceKeys.all,
}

/** The account owner's own profile per kind (the restaurant menu page also reads the restaurant's status). */
export const OWN_PROFILE_KEYS: Record<AccountKind, QueryKey[]> = {
  VENDOR: [vendorKeys.mine],
  RESTAURANT: [restaurantKeys.mine, menuKeys.mine],
  DELIVERY_PARTNER: [partnerKeys.mine],
  USER: [customerKeys.profile()],
}

const ALL_OWN_PROFILES: QueryKey[] = Object.values(OWN_PROFILE_KEYS).flat()

function ownProfiles(kind: AccountKind | undefined): QueryKey[] {
  return kind ? OWN_PROFILE_KEYS[kind] : ALL_OWN_PROFILES
}

export function invalidationForSignal(signal: Signal): InvalidationPlan {
  switch (signal.type) {
    case 'ORDER_UPDATED':
      return signal.data.returnId ? orderWithReturnsPlan(signal.data.orderId) : orderPlan(signal.data.orderId)
    case 'ADMIN_ORDERS_CHANGED':
      return adminOrdersPlan(signal.data.orderId)
    case 'STOCK_CHANGED':
    case 'PRICE_CHANGED':
      return productPlan(signal.data.productId)
    case 'RESTAURANT_UPDATED':
      return restaurantPlan(signal.data.restaurantId)
    case 'INVENTORY_UPDATED':
      return { keys: [inventoryKeys.all] }
    case 'PRICING_UPDATED':
      return { keys: [vendorPriceKeys.all] }
    case 'APPROVALS_CHANGED':
      return { keys: signal.data.kind ? [APPROVAL_QUEUE_KEYS[signal.data.kind]] : Object.values(APPROVAL_QUEUE_KEYS) }
    case 'ACCOUNT_UPDATED':
      return { keys: ownProfiles(signal.data.kind), refreshToken: true }
    case 'STORE_ORDERS_CHANGED':
      return STORE_ORDERS_PLAN
    case 'DELIVERY_OFFERS_CHANGED':
      return PARTNER_OFFERS_PLAN
    case 'DELIVERY_UPDATED':
      return PARTNER_DELIVERY_PLAN
    case 'ADMIN_DELIVERIES_CHANGED':
      return adminDeliveriesPlan(signal.data.orderId)
    case 'AI_APPROVALS_CHANGED':
      return approvalPlan(signal.data.approvalId)
    case 'REVIEW_UPDATED':
      // The customer's own reviews, what they can still review, and public lists that may now show or hide it.
      return { keys: [reviewKeys.mine(), reviewKeys.eligibleAll(), reviewKeys.publicAll()] }
    case 'ADMIN_REVIEWS_CHANGED':
      return { keys: [storeReviewKeys.admin()] }
    case 'STORE_REVIEWS_CHANGED':
      return { keys: [storeReviewKeys.store()] }
    case 'STORE_RETURNS_CHANGED':
      return { keys: [storeReturnKeys.store()] }
    case 'ADMIN_RETURNS_CHANGED':
      return { keys: [storeReturnKeys.admin(), adminOrderKeys.all] }
    case 'DELIVERY_LOCATION':
      // The dispatcher moves the partner in the cached tracking view; refetching every ping would flood the API.
      return NOTHING
    case 'RESYNC':
      return { keys: [], everything: true }
  }
}

function dataText(notification: NotificationView, name: string): string | undefined {
  const value = notification.data[name]
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

function accountKind(notification: NotificationView): AccountKind | undefined {
  const kind = dataText(notification, 'kind')
  return kind === 'VENDOR' || kind === 'RESTAURANT' || kind === 'DELIVERY_PARTNER' || kind === 'USER' ? kind : undefined
}

/**
 * What a persistent notification makes stale besides the inbox itself (which every notification refreshes).
 * The matching signals are sent too; this keeps the screens right when only the notification arrives (a replay).
 */
export function invalidationForNotification(notification: NotificationView): InvalidationPlan {
  const orderId = dataText(notification, 'orderId')
  switch (notification.type) {
    case 'ORDER_CONFIRMED':
    case 'ORDER_ACCEPTED':
    case 'ORDER_READY':
    case 'ORDER_REJECTED':
    case 'PAYMENT_FAILED':
      return orderPlan(orderId)
    case 'REFUND_COMPLETED':
      // Phase 12: a return's refund (`returnId` in data) also marks that return REFUNDED.
      return dataText(notification, 'returnId') ? customerReturnPlan(orderId) : orderPlan(orderId)
    case 'ORDER_CANCELLED':
      // The store owner is told too (its link points into the store area): its board and reports change.
      return isStoreLink(notification.link) ? { keys: [...orderPlan(orderId).keys, ...STORE_ORDERS_PLAN.keys] } : orderPlan(orderId)
    case 'NEW_ORDER':
    case 'ORDER_AUTO_REJECTED':
      // Phase 8: sent to the store owner; the board already holds the order when the alert arrives.
      return STORE_ORDERS_PLAN
    case 'REFUND_FAILED':
      return adminOrdersPlan(orderId)
    case 'LOW_STOCK':
      return { keys: [inventoryKeys.all] }
    case 'PRICE_APPROVAL_REQUESTED':
      return { keys: [APPROVAL_QUEUE_KEYS.PRICE_CHANGE] }
    case 'PRICE_CHANGE_APPROVED':
    case 'PRICE_CHANGE_REJECTED':
      return { keys: [vendorPriceKeys.all] }
    case 'APPROVAL_REQUESTED': {
      const kind = dataText(notification, 'kind')
      return kind === 'VENDOR' || kind === 'RESTAURANT' || kind === 'DELIVERY_PARTNER'
        ? { keys: [APPROVAL_QUEUE_KEYS[kind]] }
        : { keys: [APPROVAL_QUEUE_KEYS.VENDOR, APPROVAL_QUEUE_KEYS.RESTAURANT, APPROVAL_QUEUE_KEYS.DELIVERY_PARTNER] }
    }
    case 'ACCOUNT_APPROVED':
    case 'ACCOUNT_REINSTATED':
      return { keys: ownProfiles(accountKind(notification)), refreshToken: true }
    case 'ACCOUNT_REJECTED':
    case 'ACCOUNT_SUSPENDED':
      return { keys: ownProfiles(accountKind(notification)) }
    // Phase 9 — partner.
    case 'DELIVERY_OFFER':
      return PARTNER_OFFERS_PLAN
    case 'PICKUP_READY':
      return { keys: [deliveryKeys.active()] }
    case 'DELIVERY_REASSIGNED':
    case 'DELIVERY_CANCELLED':
      return PARTNER_DELIVERY_PLAN
    // Phase 9 — customer.
    case 'PARTNER_ASSIGNED':
    case 'OUT_FOR_DELIVERY':
    case 'ORDER_DELIVERED':
    case 'DELIVERY_FAILED':
      return trackedOrderPlan(orderId)
    // Phase 9 — admins.
    case 'DELIVERY_FAILED_ADMIN':
      return { keys: [...adminOrdersPlan(orderId).keys, adminDeliveryKeys.all] }
    case 'NO_DELIVERY_PARTNER':
      return { keys: [adminDeliveryKeys.all] }
    // Phase 10 — admins: the stuck-orders list, the dashboard card and the order's drawer (all under the admin order keys).
    case 'STUCK_ORDER':
      return adminOrdersPlan(orderId)
    // Phase 11: the approval request (lists, its card); a finished action also refreshes what it changed.
    case 'AI_APPROVAL_REQUESTED':
    case 'AI_APPROVAL_DECIDED':
    case 'AI_ACTION_FAILED':
      return approvalPlan(dataText(notification, 'approvalId'))
    // Phase 12 — reviews: the store's review list (NEW_REVIEW), the admin moderation queue (REVIEW_FLAGGED), the
    // customer's own reviews (REVIEW_NOT_PUBLISHED, REVIEW_REPLY).
    case 'NEW_REVIEW':
      return { keys: [storeReviewKeys.store()] }
    case 'REVIEW_FLAGGED':
      return { keys: [storeReviewKeys.admin()] }
    case 'REVIEW_NOT_PUBLISHED':
    case 'REVIEW_REPLY':
      return customerReviewPlan(notification)
    // Phase 12 — returns: the store's returns (RETURN_REQUESTED), the admin queue (RETURN_ESCALATED), the customer's
    // returns and order (RETURN_APPROVED, RETURN_REJECTED).
    case 'RETURN_REQUESTED':
      return { keys: [storeReturnKeys.store()] }
    case 'RETURN_ESCALATED':
      return { keys: [storeReturnKeys.admin()] }
    case 'RETURN_APPROVED':
    case 'RETURN_REJECTED':
      return customerReturnPlan(orderId)
    case 'AI_ACTION_COMPLETED':
      return { keys: [...approvalPlan(dataText(notification, 'approvalId')).keys, ...keysChangedBy(dataText(notification, 'action'))] }
    default:
      // A type this app does not know yet: an order or payment notification still refreshes its order.
      return (notification.category === 'ORDER' || notification.category === 'PAYMENT') && orderId ? orderPlan(orderId) : NOTHING
  }
}
