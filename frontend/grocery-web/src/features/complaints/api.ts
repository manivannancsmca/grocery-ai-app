import { httpClient } from '../../shared/api/httpClient'
import { ApiError, errorMessage, hasErrorCode, problemString } from '../../shared/api/problemDetail'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { BadgeTone } from '../../shared/components/ui'
import type { IconName } from '../../shared/icons'

/**
 * Complaints (order-service — docs/api/payment-and-support-agents.md §2). A customer raises a complaint about one of
 * their orders (or about the app in general); support admins start it and resolve it with an answer the customer sees.
 */

export const COMPLAINT_CATEGORIES = ['DELIVERY', 'PAYMENT', 'ITEMS', 'STORE', 'APP', 'OTHER'] as const
export type ComplaintCategory = (typeof COMPLAINT_CATEGORIES)[number]

/** OPEN → IN_PROGRESS → RESOLVED (OPEN → RESOLVED is allowed too; RESOLVED is final). */
export const COMPLAINT_STATUSES = ['OPEN', 'IN_PROGRESS', 'RESOLVED'] as const
export type ComplaintStatus = (typeof COMPLAINT_STATUSES)[number]

export function isComplaintStatus(value: unknown): value is ComplaintStatus {
  return typeof value === 'string' && (COMPLAINT_STATUSES as readonly string[]).includes(value)
}

export function isComplaintCategory(value: unknown): value is ComplaintCategory {
  return typeof value === 'string' && (COMPLAINT_CATEGORIES as readonly string[]).includes(value)
}

/** Text lengths the server accepts (after trimming). */
export const COMPLAINT_LIMITS = { descriptionMin: 10, descriptionMax: 1000, resolutionMin: 5, resolutionMax: 1000 } as const

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
} as const

/** `ComplaintView` — what the customer sees. */
export interface ComplaintView {
  id: string
  /** e.g. `CMP-7K3F9Q2M`. */
  complaintNumber: string
  category: ComplaintCategory | (string & {})
  status: ComplaintStatus | (string & {})
  /** Null when the complaint is not about an order. */
  orderId: string | null
  orderNumber: string | null
  storeName: string | null
  description: string
  /** The answer, set when resolved. */
  resolution: string | null
  createdAt: string
  updatedAt: string
  resolvedAt: string | null
}

/** `AdminComplaintView` — the customer view plus who raised it and which admin handled it. */
export interface AdminComplaintView extends ComplaintView {
  customerId: string
  handledBy: string | null
}

export interface NewComplaint {
  category: ComplaintCategory
  /** Leave out when the complaint is not about an order. */
  orderId?: string
  /** 10–1000 characters. */
  description: string
}

export interface MyComplaintParams {
  status?: ComplaintStatus
  page?: number
  size?: number
}

export interface AdminComplaintParams extends MyComplaintParams {
  category?: ComplaintCategory
  /** A complaint number or an order number. */
  q?: string
}

export const COMPLAINTS_PAGE_SIZE = 10

export const complaintKeys = {
  all: ['complaints'] as const,
  /** The customer's own complaints (`GET /api/complaints`). */
  mine: () => [...complaintKeys.all, 'mine'] as const,
  mineList: (params: MyComplaintParams) => [...complaintKeys.mine(), params] as const,
  /** The admin queue (`GET /api/admin/complaints`). */
  admin: () => [...complaintKeys.all, 'admin'] as const,
  adminList: (params: AdminComplaintParams) => [...complaintKeys.admin(), params] as const,
}

// ---------------------------------------------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------------------------------------------

/** 201 ComplaintView; 404 ORDER_NOT_FOUND, 409 COMPLAINT_ALREADY_OPEN / TOO_MANY_OPEN_COMPLAINTS, 400 field errors. */
export async function createComplaint(body: NewComplaint): Promise<ComplaintView> {
  return (await httpClient.post<ComplaintView>('/complaints', body)).data
}

/** The customer's own complaints, newest first. */
export async function fetchMyComplaints(params: MyComplaintParams): Promise<PageResponse<ComplaintView>> {
  return (await httpClient.get<PageResponse<ComplaintView>>('/complaints', { params: cleanParams(params) })).data
}

/** Every complaint (any admin), newest first. */
export async function fetchAdminComplaints(params: AdminComplaintParams): Promise<PageResponse<AdminComplaintView>> {
  return (await httpClient.get<PageResponse<AdminComplaintView>>('/admin/complaints', { params: cleanParams(params) })).data
}

/** OPEN → IN_PROGRESS (SUPER_ADMIN, OPS_ADMIN, SUPPORT); 409 INVALID_COMPLAINT_STATUS otherwise. */
export async function startComplaint(id: string): Promise<AdminComplaintView> {
  return (await httpClient.post<AdminComplaintView>(`/admin/complaints/${segment(id)}/start`)).data
}

/** OPEN or IN_PROGRESS → RESOLVED with the answer the customer sees (5–1000 characters). */
export async function resolveComplaint(id: string, resolution: string): Promise<AdminComplaintView> {
  return (await httpClient.post<AdminComplaintView>(`/admin/complaints/${segment(id)}/resolve`, { resolution })).data
}

// ---------------------------------------------------------------------------------------------------------------
// Labels
// ---------------------------------------------------------------------------------------------------------------

/** Friendly name, one-line hint and picture of each category (the hints follow the contract's §2.2). */
export const COMPLAINT_CATEGORY_INFO: Record<ComplaintCategory, { label: string; hint: string; icon: IconName }> = {
  DELIVERY: { label: 'Delivery', hint: 'Late, not delivered or wrong place', icon: 'scooter' },
  PAYMENT: { label: 'Payment', hint: 'Charged twice or refund not received', icon: 'card' },
  ITEMS: { label: 'Items', hint: 'Damaged, wrong, missing or poor quality', icon: 'basket' },
  STORE: { label: 'Store or restaurant', hint: 'The store’s or restaurant’s service', icon: 'store' },
  APP: { label: 'App or website', hint: 'The app or website does not work', icon: 'mobile' },
  OTHER: { label: 'Something else', hint: 'Anything else', icon: 'note' },
}

export function complaintCategoryLabel(category: string): string {
  return isComplaintCategory(category) ? COMPLAINT_CATEGORY_INFO[category].label : category
}

export function complaintCategoryIcon(category: string): IconName {
  return isComplaintCategory(category) ? COMPLAINT_CATEGORY_INFO[category].icon : 'chat'
}

export const COMPLAINT_STATUS_LABELS: Record<ComplaintStatus, string> = {
  OPEN: 'Open',
  IN_PROGRESS: 'In progress',
  RESOLVED: 'Resolved',
}

const COMPLAINT_STATUS_TONES: Record<ComplaintStatus, BadgeTone> = {
  OPEN: 'amber',
  IN_PROGRESS: 'blue',
  RESOLVED: 'green',
}

export function complaintStatusLabel(status: string): string {
  return isComplaintStatus(status) ? COMPLAINT_STATUS_LABELS[status] : status
}

export function complaintStatusTone(status: string): BadgeTone {
  return isComplaintStatus(status) ? COMPLAINT_STATUS_TONES[status] : 'gray'
}

/** First 8 characters of an id, for admins ("1a2b3c4d…"); the full id goes in a tooltip. */
export function shortId(id: string): string {
  return id.length > 8 ? `${id.slice(0, 8)}…` : id
}

// ---------------------------------------------------------------------------------------------------------------
// Error messages
// ---------------------------------------------------------------------------------------------------------------

/** The open complaint's number from a COMPLAINT_ALREADY_OPEN problem (an extension field, or inside its detail). */
function openComplaintNumber(error: ApiError): string | null {
  return problemString(error, 'complaintNumber') ?? error.problem?.detail?.match(/CMP-[0-9A-Z]+/)?.[0] ?? null
}

/** Friendly text for a refused new complaint (field errors are shown on the fields instead). */
export function createComplaintErrorMessage(error: unknown): string {
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
 */
export function isComplaintConflict(error: unknown): boolean {
  return (
    hasErrorCode(error, COMPLAINT_ERRORS.invalidStatus, COMPLAINT_ERRORS.notFound, COMPLAINT_ERRORS.concurrentChange) ||
    (error instanceof ApiError && error.status === 409)
  )
}

export function complaintConflictMessage(complaintNumber: string, error: unknown): string {
  if (hasErrorCode(error, COMPLAINT_ERRORS.notFound)) return `${complaintNumber} no longer exists. The list has been refreshed.`
  if (hasErrorCode(error, COMPLAINT_ERRORS.invalidStatus)) {
    return `${complaintNumber} was already started or resolved by someone else. The list has been refreshed — check its status.`
  }
  return `Someone else changed ${complaintNumber} at the same time. The list has been refreshed — check it and try again.`
}
