import { hasErrorCode, problemString } from '../../shared/api/problemDetail'
import { roleLabel } from '../../shared/auth/roles'
import { formatPrice, humanizeToken } from '../../shared/utils/format'
import { adminDeliveryKeys } from '../admin-deliveries/api'
import { adminOrderKeys } from '../admin-orders/api'
import { adminPaymentKeys } from '../admin-payments/api'
import { cartKeys } from '../cart/api'
import { menuKeys } from '../menu/api'
import { orderKeys } from '../orders/api'
import { purchasingKeys } from '../purchasing/api'
import { restaurantKeys } from '../restaurant/api'
import { returnKeys as customerReturnKeys } from '../returns/api'
import { storeOrderKeys } from '../store-orders/api'
import { vendorKeys } from '../vendor/api'
import { inventoryKeys } from '../vendor-inventory/api'
import { vendorPriceKeys } from '../vendor-pricing/api'
import { AI_ERRORS } from './api'

/** @typedef {import('@tanstack/react-query').QueryKey} QueryKey */
/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('./api').ApprovalAction} ApprovalAction */
/** @typedef {import('./api').ApprovalStatus} ApprovalStatus */
/** @typedef {import('./api').ApprovalView} ApprovalView */
/** @typedef {import('./api').InvocationStatus} InvocationStatus */
/** @typedef {import('./api').ToolStatus} ToolStatus */

/**
 * @template {string} T
 * @param {Record<T, string>} labels
 * @param {string} value
 * @returns {string}
 */
function labelOf(labels, value) {
  return value in labels ? labels[value] : humanizeToken(value)
}

/**
 * @template {string} T
 * @param {Record<T, BadgeTone>} tones
 * @param {string} value
 * @returns {BadgeTone}
 */
function toneOf(tones, value) {
  return value in tones ? tones[value] : 'gray'
}

/** @type {Record<string, string>} */
export const AGENT_LABELS = {
  shopping: 'Shopping assistant',
  'admin-bi': 'Business-intelligence assistant',
  // Phase 12.
  vendor: 'Vendor assistant',
  restaurant: 'Restaurant assistant',
  delivery: 'Delivery assistant',
}

/**
 * Where each agent's user sees and decides what it prepared (the notification links of contract §6, all with
 * `?id=`), and the name of that page in the panel.
 * @type {Record<string, { path: string, label: string }>}
 */
export const AGENT_APPROVALS_PAGE = {
  shopping: { path: '/assistant/approvals', label: 'Assistant actions' },
  'admin-bi': { path: '/admin/ai/approvals', label: 'AI approvals' },
  vendor: { path: '/vendor/assistant', label: 'Assistant actions' },
  restaurant: { path: '/restaurant/assistant', label: 'Assistant actions' },
  delivery: { path: '/delivery/assistant', label: 'Assistant actions' },
}

/**
 * @param {string} agent
 * @returns {string}
 */
export function agentLabel(agent) {
  return AGENT_LABELS[agent] ?? humanizeToken(agent.replace(/-/g, '_'))
}

/** @type {Record<ApprovalStatus, string>} */
export const APPROVAL_STATUS_LABELS = {
  PENDING_APPROVAL: 'Waiting for approval',
  APPROVED: 'Approved',
  EXECUTING: 'Running',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
  REJECTED: 'Not approved',
  EXPIRED: 'Expired',
  CANCELLED: 'Cancelled',
}

/** @type {Record<ApprovalStatus, BadgeTone>} */
export const APPROVAL_STATUS_TONES = {
  PENDING_APPROVAL: 'amber',
  APPROVED: 'blue',
  EXECUTING: 'blue',
  COMPLETED: 'green',
  FAILED: 'red',
  REJECTED: 'gray',
  EXPIRED: 'gray',
  CANCELLED: 'gray',
}

/**
 * @param {string} status
 * @returns {string}
 */
export function approvalStatusLabel(status) {
  return labelOf(APPROVAL_STATUS_LABELS, status)
}

/**
 * @param {string} status
 * @returns {BadgeTone}
 */
export function approvalStatusTone(status) {
  return toneOf(APPROVAL_STATUS_TONES, status)
}

/** @type {Record<ApprovalAction, string>} */
export const APPROVAL_ACTION_LABELS = {
  PLACE_ORDER: 'Place order',
  CANCEL_ORDER: 'Cancel order',
  REFUND_PAYMENT: 'Refund payment',
  ADMIN_CANCEL_ORDER: 'Cancel order (admin)',
  SUSPEND_VENDOR: 'Suspend vendor',
  SUSPEND_RESTAURANT: 'Suspend restaurant',
  REQUEST_RETURN: 'Request a return',
  ADJUST_STOCK: 'Adjust stock',
  CHANGE_PRICE: 'Change price',
  SEND_PURCHASE_ORDER: 'Send purchase order',
  SET_MENU_ITEM_AVAILABILITY: 'Dish availability',
  ACCEPT_STORE_ORDER: 'Accept order',
  REJECT_STORE_ORDER: 'Reject order',
  REASSIGN_DELIVERY: 'Reassign delivery',
}

/**
 * @param {string} action
 * @returns {string}
 */
export function approvalActionLabel(action) {
  return labelOf(APPROVAL_ACTION_LABELS, action)
}

/**
 * The "Confirm" button of a self-decided action (the customer's, the vendor's or the restaurant owner's own request).
 * @type {Record<string, string>}
 */
const CONFIRM_LABELS = {
  PLACE_ORDER: 'Confirm order',
  CANCEL_ORDER: 'Confirm cancellation',
  REQUEST_RETURN: 'Confirm return',
  ADJUST_STOCK: 'Confirm stock change',
  CHANGE_PRICE: 'Confirm price change',
  SEND_PURCHASE_ORDER: 'Send purchase order',
  SET_MENU_ITEM_AVAILABILITY: 'Confirm',
  ACCEPT_STORE_ORDER: 'Accept order',
  REJECT_STORE_ORDER: 'Reject order',
}

/**
 * @param {string} action
 * @returns {string}
 */
export function approvalConfirmLabel(action) {
  return CONFIRM_LABELS[action] ?? 'Confirm'
}

/**
 * A string or id property of a COMPLETED action's `result`, or null.
 * @param {Pick<ApprovalView, 'result'>} approval
 * @param {string} name
 * @returns {string | null}
 */
function resultText(approval, name) {
  const value = approval.result?.[name]
  return typeof value === 'string' && value ? value : null
}

/**
 * Phase 12: what a COMPLETED store or delivery action changed, in words — e.g. a price change above the
 * threshold that pricing-service sent on to an admin (`result.outcome = PENDING_APPROVAL`), or the target's own
 * `result.message`. Null when there is nothing to add.
 * @param {Pick<ApprovalView, 'action' | 'result'>} approval
 * @returns {string | null}
 */
export function approvalResultNote(approval) {
  if (approval.action === 'REQUEST_RETURN') {
    // Phase 12 §6: the target answered the ReturnView, with the refund order-service worked out.
    const returnNumber = resultText(approval, 'returnNumber')
    const refund = approval.result?.refundAmount
    const amount = typeof refund === 'number' && Number.isFinite(refund) ? formatPrice(refund) : null
    return [
      returnNumber ? `Return ${returnNumber} requested.` : 'Return requested.',
      amount ? `If the store approves it, ${amount} will be refunded.` : 'The store decides on it next.',
    ].join(' ')
  }
  if (approval.action === 'CHANGE_PRICE' && resultText(approval, 'outcome') === 'PENDING_APPROVAL') {
    return 'The change is above the price threshold, so it now waits for an admin’s approval. The current price stays live until then.'
  }
  return resultText(approval, 'message')
}

/**
 * Phase 12: where to see what a COMPLETED store or delivery action changed (null: no page for it).
 * @param {Pick<ApprovalView, 'action' | 'result'>} approval
 * @returns {{ to: string, label: string } | null}
 */
export function approvalResultLink(approval) {
  switch (approval.action) {
    case 'REQUEST_RETURN':
      return { to: '/account/returns', label: 'See my returns' }
    case 'ADJUST_STOCK':
      return { to: '/vendor/inventory', label: 'Open inventory' }
    case 'CHANGE_PRICE':
      return { to: '/vendor/pricing', label: 'Open pricing' }
    case 'SEND_PURCHASE_ORDER': {
      const id = resultText(approval, 'purchaseOrderId') ?? resultText(approval, 'id')
      return id
        ? { to: `/vendor/purchase-orders/${encodeURIComponent(id)}`, label: 'Open the purchase order' }
        : { to: '/vendor/purchase-orders', label: 'Open purchase orders' }
    }
    case 'SET_MENU_ITEM_AVAILABILITY':
      return { to: '/restaurant/menu', label: 'Open the menu' }
    case 'ACCEPT_STORE_ORDER':
    case 'REJECT_STORE_ORDER':
      return { to: '/restaurant/orders', label: 'Open the order board' }
    case 'REASSIGN_DELIVERY':
      return { to: '/admin/deliveries', label: 'Open deliveries' }
    default:
      return null
  }
}

/**
 * Tool chips: the server's `label` wins; these name tools in the audit list.
 * @type {Record<string, string>}
 */
export const TOOL_LABELS = {
  searchProducts: 'Search products',
  getProduct: 'Product details',
  searchRestaurants: 'Search restaurants',
  getRestaurantMenu: 'Restaurant menu',
  getCart: 'Read cart',
  addToCart: 'Add to cart',
  updateCartItem: 'Change cart item',
  previewCheckout: 'Checkout preview',
  listMyOrders: 'List my orders',
  getOrderStatus: 'Order status',
  placeOrder: 'Place order',
  cancelOrder: 'Cancel order',
  // Phase 12 §6
  requestReturn: 'Request a return',
  getOrderStats: 'Order stats',
  listOrders: 'List orders',
  getOrder: 'Order details',
  listStuckOrders: 'Stuck orders',
  listPayments: 'List payments',
  getReconciliationSummary: 'Reconciliation summary',
  refundOrder: 'Refund order',
  cancelOrderAsAdmin: 'Cancel order (admin)',
  suspendVendor: 'Suspend vendor',
  suspendRestaurant: 'Suspend restaurant',
  // Phase 12: vendor, restaurant and delivery assistants.
  getSalesReport: 'Sales report',
  listInventory: 'List inventory',
  getStock: 'Stock of a product',
  listProducts: 'List products',
  getPrice: 'Price of a product',
  listStoreOrders: 'List store orders',
  listPurchaseOrders: 'List purchase orders',
  listSuppliers: 'List suppliers',
  listReturns: 'List returns',
  listReviews: 'List reviews',
  draftPurchaseOrder: 'Draft purchase order',
  adjustStock: 'Adjust stock',
  changePrice: 'Change price',
  sendPurchaseOrder: 'Send purchase order',
  getOrderQueue: 'Order queue',
  getMenu: 'Read menu',
  setMenuItemAvailability: 'Dish availability',
  acceptOrder: 'Accept order',
  rejectOrder: 'Reject order',
  getActiveDelivery: 'Active delivery',
  getMyOffers: 'My offers',
  getMyHistory: 'My delivery history',
  getMySummary: 'My summary',
  listDeliveries: 'List deliveries',
  getDelivery: 'Delivery details',
  reassignDelivery: 'Reassign delivery',
}

/**
 * The tools of each agent (contract §3), for the audit filter.
 * @type {Record<string, readonly string[]>}
 */
export const AGENT_TOOLS = {
  shopping: [
    'searchProducts',
    'getProduct',
    'searchRestaurants',
    'getRestaurantMenu',
    'getCart',
    'addToCart',
    'updateCartItem',
    'previewCheckout',
    'listMyOrders',
    'getOrderStatus',
    'placeOrder',
    'cancelOrder',
    // Phase 12 §6
    'requestReturn',
  ],
  'admin-bi': [
    'getOrderStats',
    'listOrders',
    'getOrder',
    'listStuckOrders',
    'listPayments',
    'getReconciliationSummary',
    'refundOrder',
    'cancelOrderAsAdmin',
    'suspendVendor',
    'suspendRestaurant',
  ],
  // Phase 12 (contract §6).
  vendor: [
    'getSalesReport',
    'listInventory',
    'getStock',
    'listProducts',
    'getPrice',
    'listStoreOrders',
    'listPurchaseOrders',
    'listSuppliers',
    'listReturns',
    'listReviews',
    'draftPurchaseOrder',
    'adjustStock',
    'changePrice',
    'sendPurchaseOrder',
  ],
  restaurant: [
    'getOrderQueue',
    'getSalesReport',
    'getMenu',
    'listReturns',
    'listReviews',
    'setMenuItemAvailability',
    'acceptOrder',
    'rejectOrder',
  ],
  delivery: [
    'getActiveDelivery',
    'getMyOffers',
    'getMyHistory',
    'getMySummary',
    'listDeliveries',
    'getDelivery',
    'reassignDelivery',
  ],
}

/**
 * @param {string | null} tool
 * @returns {string}
 */
export function toolLabel(tool) {
  if (!tool) return 'Agent access'
  return TOOL_LABELS[tool] ?? tool
}

/**
 * The cart changed: these tools refresh it when they succeed.
 * @type {readonly string[]}
 */
export const CART_WRITE_TOOLS = ['addToCart', 'updateCartItem']

/** @type {Record<ToolStatus, string>} */
export const TOOL_STATUS_LABELS = {
  STARTED: 'Working',
  SUCCEEDED: 'Done',
  FAILED: 'Failed',
  BLOCKED: 'Blocked',
  APPROVAL_REQUIRED: 'Needs approval',
}

/**
 * @param {string} status
 * @returns {string}
 */
export function toolStatusLabel(status) {
  return labelOf(TOOL_STATUS_LABELS, status)
}

/** @type {Record<InvocationStatus, string>} */
export const INVOCATION_STATUS_LABELS = {
  SUCCEEDED: 'Succeeded',
  FAILED: 'Failed',
  BLOCKED: 'Blocked',
  APPROVAL_REQUIRED: 'Approval required',
}

/** @type {Record<InvocationStatus, BadgeTone>} */
export const INVOCATION_STATUS_TONES = {
  SUCCEEDED: 'green',
  FAILED: 'red',
  BLOCKED: 'red',
  APPROVAL_REQUIRED: 'amber',
}

/**
 * @param {string} status
 * @returns {string}
 */
export function invocationStatusLabel(status) {
  return labelOf(INVOCATION_STATUS_LABELS, status)
}

/**
 * @param {string} status
 * @returns {BadgeTone}
 */
export function invocationStatusTone(status) {
  return toneOf(INVOCATION_STATUS_TONES, status)
}

/**
 * ToolGuard's reasons (contract §3) in words.
 * @type {Record<string, string>}
 */
export const BLOCK_REASON_LABELS = {
  TOOL_NOT_ALLOWED: 'Tool not allowed for this assistant',
  ROLE_NOT_ALLOWED: 'Role may not propose this',
  INVALID_ARGUMENTS: 'Invalid arguments',
  AGENT_NOT_ALLOWED: 'Assistant not allowed for this user',
}

/**
 * @param {string | null} reason
 * @returns {string | null}
 */
export function blockReasonLabel(reason) {
  if (!reason) return null
  return BLOCK_REASON_LABELS[reason] ?? reason
}

/**
 * "Super admin or Finance admin".
 * @param {readonly string[]} roles
 * @returns {string}
 */
export function rolesLabel(roles) {
  const names = roles.map(roleLabel)
  if (names.length <= 1) return names[0] ?? ''
  return `${names.slice(0, -1).join(', ')} or ${names.at(-1)}`
}

/** What a customer's "Not now" sends as the required rejection comment. */
export const NOT_NOW_COMMENT = 'Not now'

/**
 * Waiting for a decision (and not past its expiry yet).
 * @param {Pick<ApprovalView, 'status'>} approval
 * @returns {boolean}
 */
export function isPending(approval) {
  return approval.status === 'PENDING_APPROVAL'
}

/**
 * `result.orderNumber` / `result.orderId` of a COMPLETED order action, when the target answered them.
 * @param {Pick<ApprovalView, 'result'>} approval
 * @returns {{ orderId: string | null, orderNumber: string | null }}
 */
export function resultOrder(approval) {
  const result = approval.result ?? {}
  const orderId = typeof result.orderId === 'string' && result.orderId ? result.orderId : null
  const orderNumber = typeof result.orderNumber === 'string' && result.orderNumber ? result.orderNumber : null
  return { orderId, orderNumber }
}

/**
 * What a COMPLETED action changed, per action (the matching live events refresh them too; this is immediate).
 * @param {string | null | undefined} action
 * @returns {QueryKey[]}
 */
export function keysChangedBy(action) {
  switch (action) {
    case 'PLACE_ORDER':
      return [cartKeys.all, orderKeys.all]
    case 'CANCEL_ORDER':
      return [orderKeys.all]
    case 'REQUEST_RETURN':
      return [orderKeys.all, customerReturnKeys.all]
    case 'REFUND_PAYMENT':
      return [adminPaymentKeys.all, adminOrderKeys.all, adminOrderKeys.allStats()]
    case 'ADMIN_CANCEL_ORDER':
      return [adminOrderKeys.all, adminOrderKeys.allStats(), adminPaymentKeys.all]
    case 'SUSPEND_VENDOR':
      return [vendorKeys.admin]
    case 'SUSPEND_RESTAURANT':
      return [restaurantKeys.admin]
    // Phase 12: store and delivery assistants.
    case 'ADJUST_STOCK':
      return [inventoryKeys.all]
    case 'CHANGE_PRICE':
      return [vendorPriceKeys.all]
    case 'SEND_PURCHASE_ORDER':
      return [purchasingKeys.all]
    case 'SET_MENU_ITEM_AVAILABILITY':
      return [menuKeys.mine]
    case 'ACCEPT_STORE_ORDER':
    case 'REJECT_STORE_ORDER':
      return [storeOrderKeys.all]
    case 'REASSIGN_DELIVERY':
      return [adminDeliveryKeys.all]
    default:
      return []
  }
}

/**
 * The message for a refused decision: the four-eyes rule, a request that is no longer pending (naming its
 * `currentStatus`), a role that may not decide — or null for anything else.
 * @param {unknown} error
 * @returns {string | null}
 */
export function decisionErrorMessage(error) {
  if (hasErrorCode(error, AI_ERRORS.fourEyesRequired)) {
    return 'You proposed this request, so another admin has to decide it (four-eyes rule). Nothing was done.'
  }
  if (hasErrorCode(error, AI_ERRORS.approvalNotPending)) {
    const current = problemString(error, 'currentStatus')
    if (current === 'EXPIRED')
      return 'This request has expired, so nothing was done. Ask the assistant again if it is still needed.'
    const now = current ? ` (now: ${approvalStatusLabel(current)})` : ''
    return `This request was already decided${now}, so nothing was done this time. It has been refreshed.`
  }
  if (hasErrorCode(error, AI_ERRORS.approvalForbidden)) {
    return 'Your role cannot decide this request. Nothing was done.'
  }
  if (hasErrorCode(error, AI_ERRORS.approvalNotFound)) {
    return 'This request no longer exists.'
  }
  return null
}
