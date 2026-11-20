import { httpClient } from '../../shared/api/httpClient'
import { ApiError, errorMessage, hasErrorCode, problemString } from '../../shared/api/problemDetail'
import { cleanParams, segment } from '../../shared/api/types'
/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('../../shared/icons').IconName} IconName */

/**
 * Complaints (order-service — docs/api/payment-and-support-agents.md §2). A customer raises a complaint about one of
 * their orders (or about the app in general); support admins start it and resolve it with an answer the customer sees.
 */

export const COMPLAINT_CATEGORIES = ['DELIVERY', 'PAYMENT', 'ITEMS', 'STORE', 'APP', 'OTHER']
/** @typedef {(typeof COMPLAINT_CATEGORIES)[number]} ComplaintCategory */

/** OPEN → IN_PROGRESS → RESOLVED (OPEN → RESOLVED is allowed too; RESOLVED is final). */
export const COMPLAINT_STATUSES = ['OPEN', 'IN_PROGRESS', 'RESOLVED']
/** @typedef {(typeof COMPLAINT_STATUSES)[number]} ComplaintStatus */

/**
 * @param {unknown} value
 * @returns {value is ComplaintStatus}
 */
export function isComplaintStatus(value) {
  return typeof value === 'string' && COMPLAINT_STATUSES.includes(value)
}

/**
 * @param {unknown} value
 * @returns {value is ComplaintCategory}
 */
export function isComplaintCategory(value) {
  return typeof value === 'string' && COMPLAINT_CATEGORIES.includes(value)
}

/** Text lengths the server accepts (after trimming). */
export const COMPLAINT_LIMITS = { descriptionMin: 10, descriptionMax: 1000, resolutionMin: 5, resolutionMax: 1000 }

export const COMPLAINT_ERRORS = {
  /** The order already has an OPEN or IN_PROGRESS complaint (the problem's detail names it). */
  alreadyOpen: 'COMPLAINT_ALREADY_OPEN',
  /** The customer has reached the most open complaints allowed (5 by default). */
  tooMany: 'TOO_MANY_OPEN_COMPLAINTS',
  /** start / resolve from a status that does not allow it (someone else was quicker). */
  invalidStatus: 'INVALID_COMPLAINT_STATUS',
  notFound: 'COMPLAINT_NOT_FOUND',
  /** The chosen order is not one of the customer's. */
  orderNotFound: 'ORDER_NOT_FOUND',
  /** Two admins changed the same complaint at the same time (optimistic lock). */
  concurrentChange: 'CONCURRENT_MODIFICATION',
}

/**
 * `ComplaintView` — what the customer sees.
 * @typedef {object} ComplaintView
 * @property {string} id
 * @property {string} complaintNumber e.g. `CMP-7K3F9Q2M`.
 * @property {ComplaintCategory | (string & {})} category
 * @property {ComplaintStatus | (string & {})} status
 * @property {string | null} orderId Null when the complaint is not about an order.
 * @property {string | null} orderNumber
 * @property {string | null} storeName
 * @property {string} description
 * @property {string | null} resolution The answer, set when resolved.
 * @property {string} createdAt
 * @property {string} updatedAt
 * @property {string | null} resolvedAt
 */

/**
 * `AdminComplaintView` — the customer view plus who raised it and which admin handled it.
 * @typedef {ComplaintView & { customerId: string, handledBy: string | null }} AdminComplaintView
 */

/**
 * @typedef {object} NewComplaint
 * @property {ComplaintCategory} category
 * @property {string} [orderId] Leave out when the complaint is not about an order.
 * @property {string} description 10–1000 characters.
 */

/**
 * @typedef {object} MyComplaintParams
 * @property {ComplaintStatus} [status]
 * @property {number} [page]
 * @property {number} [size]
 */

/**
 * @typedef {MyComplaintParams & { category?: ComplaintCategory, q?: string }} AdminComplaintParams
 * - `q`: A complaint number or an order number.
 */

export const COMPLAINTS_PAGE_SIZE = 10

export const complaintKeys = {
  all: ['complaints'],
  /** The customer's own complaints (`GET /api/complaints`). */
  mine: () => [...complaintKeys.all, 'mine'],
  mineList: (params) => [...complaintKeys.mine(), params],
  /** The admin queue (`GET /api/admin/complaints`). */
  admin: () => [...complaintKeys.all, 'admin'],
  adminList: (params) => [...complaintKeys.admin(), params],
}

// ---------------------------------------------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------------------------------------------

/**
 * 201 ComplaintView; 404 ORDER_NOT_FOUND, 409 COMPLAINT_ALREADY_OPEN / TOO_MANY_OPEN_COMPLAINTS, 400 field errors.
 * @param {NewComplaint} body
 * @returns {Promise<ComplaintView>}
 */
export async function createComplaint(body) {
  return (await httpClient.post('/complaints', body)).data
}

/**
 * The customer's own complaints, newest first.
 * @param {MyComplaintParams} params
 * @returns {Promise<PageResponse<ComplaintView>>}
 */
export async function fetchMyComplaints(params) {
  return (await httpClient.get('/complaints', { params: cleanParams(params) })).data
}

/**
 * Every complaint (any admin), newest first.
 * @param {AdminComplaintParams} params
 * @returns {Promise<PageResponse<AdminComplaintView>>}
 */
export async function fetchAdminComplaints(params) {
  return (await httpClient.get('/admin/complaints', { params: cleanParams(params) })).data
}

/**
 * OPEN → IN_PROGRESS (SUPER_ADMIN, OPS_ADMIN, SUPPORT); 409 INVALID_COMPLAINT_STATUS otherwise.
 * @param {string} id
 * @returns {Promise<AdminComplaintView>}
 */
export async function startComplaint(id) {
  return (await httpClient.post(`/admin/complaints/${segment(id)}/start`)).data
}

/**
 * OPEN or IN_PROGRESS → RESOLVED with the answer the customer sees (5–1000 characters).
 * @param {string} id
 * @param {string} resolution
 * @returns {Promise<AdminComplaintView>}
 */
export async function resolveComplaint(id, resolution) {
  return (await httpClient.post(`/admin/complaints/${segment(id)}/resolve`, { resolution })).data
}

// ---------------------------------------------------------------------------------------------------------------
// Labels
// ---------------------------------------------------------------------------------------------------------------

/**
 * Friendly name, one-line hint and picture of each category (the hints follow the contract's §2.2).
 * @type {Record<ComplaintCategory, { label: string, hint: string, icon: IconName }>}
 */
export const COMPLAINT_CATEGORY_INFO = {
  DELIVERY: { label: 'Delivery', hint: 'Late, not delivered or wrong place', icon: 'scooter' },
  PAYMENT: { label: 'Payment', hint: 'Charged twice or refund not received', icon: 'card' },
  ITEMS: { label: 'Items', hint: 'Damaged, wrong, missing or poor quality', icon: 'basket' },
  STORE: { label: 'Store or restaurant', hint: 'The store’s or restaurant’s service', icon: 'store' },
  APP: { label: 'App or website', hint: 'The app or website does not work', icon: 'mobile' },
  OTHER: { label: 'Something else', hint: 'Anything else', icon: 'note' },
}

/**
 * @param {string} category
 * @returns {string}
 */
export function complaintCategoryLabel(category) {
  return isComplaintCategory(category) ? COMPLAINT_CATEGORY_INFO[category].label : category
}

/**
 * @param {string} category
 * @returns {IconName}
 */
export function complaintCategoryIcon(category) {
  return isComplaintCategory(category) ? COMPLAINT_CATEGORY_INFO[category].icon : 'chat'
}

/** @type {Record<ComplaintStatus, string>} */
export const COMPLAINT_STATUS_LABELS = {
  OPEN: 'Open',
  IN_PROGRESS: 'In progress',
  RESOLVED: 'Resolved',
}

/** @type {Record<ComplaintStatus, BadgeTone>} */
const COMPLAINT_STATUS_TONES = {
  OPEN: 'amber',
  IN_PROGRESS: 'blue',
  RESOLVED: 'green',
}

/**
 * @param {string} status
 * @returns {string}
 */
export function complaintStatusLabel(status) {
  return isComplaintStatus(status) ? COMPLAINT_STATUS_LABELS[status] : status
}

/**
 * @param {string} status
 * @returns {BadgeTone}
 */
export function complaintStatusTone(status) {
  return isComplaintStatus(status) ? COMPLAINT_STATUS_TONES[status] : 'gray'
}

/**
 * First 8 characters of an id, for admins ("1a2b3c4d…"); the full id goes in a tooltip.
 * @param {string} id
 * @returns {string}
 */
export function shortId(id) {
  return id.length > 8 ? `${id.slice(0, 8)}…` : id
}

// ---------------------------------------------------------------------------------------------------------------
// Error messages
// ---------------------------------------------------------------------------------------------------------------

/**
 * The open complaint's number from a COMPLAINT_ALREADY_OPEN problem (an extension field, or inside its detail).
 * @param {ApiError} error
 * @returns {string | null}
 */
function openComplaintNumber(error) {
  return problemString(error, 'complaintNumber') ?? error.problem?.detail?.match(/CMP-[0-9A-Z]+/)?.[0] ?? null
}

/**
 * Friendly text for a refused new complaint (field errors are shown on the fields instead).
 * @param {unknown} error
 * @returns {string}
 */
export function createComplaintErrorMessage(error) {
  if (hasErrorCode(error, COMPLAINT_ERRORS.alreadyOpen)) {
    const number = openComplaintNumber(error)
    return `You already have an open complaint about this order${number ? ` (${number})` : ''}. Please wait for our answer to it below, or choose another order.`
  }
  if (hasErrorCode(error, COMPLAINT_ERRORS.tooMany)) {
    return 'You have too many open complaints. Please wait until we answer one of them before raising another.'
  }
  if (hasErrorCode(error, COMPLAINT_ERRORS.orderNotFound) || (error instanceof ApiError && error.status === 404)) {
    return 'We could not find that order on your account. Choose another order, or “Not about an order”.'
  }
  return errorMessage(error)
}

/**
 * True when a start / resolve failed because the complaint changed meanwhile (another admin started or resolved it,
 * or changed it at the same moment): the list is refreshed and the admin is told what happened.
 * @param {unknown} error
 * @returns {boolean}
 */
export function isComplaintConflict(error) {
  return (
    hasErrorCode(error, COMPLAINT_ERRORS.invalidStatus, COMPLAINT_ERRORS.notFound, COMPLAINT_ERRORS.concurrentChange) ||
    (error instanceof ApiError && error.status === 409)
  )
}

/**
 * @param {string} complaintNumber
 * @param {unknown} error
 * @returns {string}
 */
export function complaintConflictMessage(complaintNumber, error) {
  if (hasErrorCode(error, COMPLAINT_ERRORS.notFound))
    return `${complaintNumber} no longer exists. The list has been refreshed.`
  if (hasErrorCode(error, COMPLAINT_ERRORS.invalidStatus)) {
    return `${complaintNumber} was already started or resolved by someone else. The list has been refreshed — check its status.`
  }
  return `Someone else changed ${complaintNumber} at the same time. The list has been refreshed — check it and try again.`
}
