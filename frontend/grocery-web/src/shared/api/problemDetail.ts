import { isAxiosError } from 'axios'
import { CORRELATION_ID_HEADER } from './correlation'

/** One entry of the `errors` array the services add to a VALIDATION_FAILED / PROFILE_INCOMPLETE problem. */
export interface FieldErrorDetail {
  field: string
  message: string
}

/** RFC 9457 Problem Details — the error body every backend service returns. */
export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  /** Machine-readable code, e.g. `EMAIL_ALREADY_REGISTERED` (see the Phase 2 API contract). */
  errorCode?: string
  correlationId?: string
  /** Field-level errors (VALIDATION_FAILED, PROFILE_INCOMPLETE). */
  errors?: FieldErrorDetail[]
  [extension: string]: unknown
}

export type ApiErrorKind = 'http' | 'network' | 'timeout' | 'unknown'

interface ApiErrorInit {
  kind: ApiErrorKind
  status: number | null
  problem: ProblemDetail | null
  correlationId: string | null
  cause?: unknown
}

/** The single error type every API call rejects with. */
export class ApiError extends Error {
  readonly kind: ApiErrorKind
  readonly status: number | null
  readonly problem: ProblemDetail | null
  readonly correlationId: string | null

  constructor(message: string, init: ApiErrorInit) {
    super(message, { cause: init.cause })
    this.name = 'ApiError'
    this.kind = init.kind
    this.status = init.status
    this.problem = init.problem
    this.correlationId = init.correlationId
  }

  /** The contract's `errorCode`, or null when the body had none. */
  get errorCode(): string | null {
    const code = this.problem?.errorCode
    return typeof code === 'string' && code.length > 0 ? code : null
  }

  /** Field errors from the problem body, normalised to `{ field, message }`. */
  get fieldErrors(): FieldErrorDetail[] {
    return parseFieldErrors(this.problem?.errors)
  }
}

/** True when `error` is an ApiError carrying one of the given error codes. */
export function hasErrorCode(error: unknown, ...codes: string[]): error is ApiError {
  return error instanceof ApiError && error.errorCode !== null && codes.includes(error.errorCode)
}

/**
 * A numeric extension property of the problem body (e.g. `available` on `INSUFFICIENT_STOCK`), or null.
 * Spring writes extensions at the top level, or under `properties` when its Jackson mixin is not active.
 */
export function problemNumber(error: unknown, name: string): number | null {
  const value = problemProperty(error, name)
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/** A string extension property of the problem body (e.g. `productId` on a failed bulk stock update), or null. */
export function problemString(error: unknown, name: string): string | null {
  const value = problemProperty(error, name)
  return typeof value === 'string' && value.length > 0 ? value : null
}

/** Any extension property of the problem body (e.g. the `issues` array of `CHECKOUT_BLOCKED`), or undefined. */
export function problemProperty(error: unknown, name: string): unknown {
  if (!(error instanceof ApiError) || !error.problem) return undefined
  const nested = error.problem.properties
  return error.problem[name] ?? (typeof nested === 'object' && nested !== null ? (nested as Record<string, unknown>)[name] : undefined)
}

/** A user-facing message for anything thrown by an API call or form handler. */
export function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message
  return 'Something went wrong. Please try again.'
}

function parseFieldErrors(raw: unknown): FieldErrorDetail[] {
  if (Array.isArray(raw)) {
    return raw.flatMap((entry: unknown) => {
      if (typeof entry !== 'object' || entry === null) return []
      const { field, message } = entry as Record<string, unknown>
      return typeof field === 'string' && typeof message === 'string' ? [{ field, message }] : []
    })
  }
  // Tolerate the `{ field: [messages] }` shape some Spring setups produce.
  if (typeof raw === 'object' && raw !== null) {
    return Object.entries(raw as Record<string, unknown>).flatMap(([field, value]) => {
      const messages = Array.isArray(value) ? value : [value]
      return messages.filter((m): m is string => typeof m === 'string').map((message) => ({ field, message }))
    })
  }
  return []
}

export function isProblemDetail(value: unknown): value is ProblemDetail {
  if (typeof value !== 'object' || value === null) return false
  // A Blob (the error body of a download request) has a string `type` too: it is read separately.
  if (typeof Blob !== 'undefined' && value instanceof Blob) return false
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.title === 'string' ||
    typeof candidate.detail === 'string' ||
    typeof candidate.type === 'string' ||
    typeof candidate.errorCode === 'string'
  )
}

function readHeader(headers: unknown, name: string): string | null {
  if (typeof headers !== 'object' || headers === null) return null
  const record = headers as Record<string, unknown> & { get?: (key: string) => unknown }
  const value = typeof record.get === 'function' ? record.get(name) : (record[name] ?? record[name.toLowerCase()])
  return typeof value === 'string' && value.length > 0 ? value : null
}

function httpMessage(status: number, problem: ProblemDetail | null): string {
  if (problem?.detail) return problem.detail
  if (problem?.title) return problem.title
  if (status === 502 || status === 503 || status === 504) {
    return `The service is temporarily unavailable (HTTP ${status}).`
  }
  return `Request failed with status ${status}.`
}

/** Converts anything thrown by axios (or elsewhere) into an ApiError. */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error

  if (isAxiosError(error)) {
    const headerCorrelationId =
      readHeader(error.response?.headers, CORRELATION_ID_HEADER) ??
      readHeader(error.config?.headers, CORRELATION_ID_HEADER)

    if (error.response) {
      const status = error.response.status
      const problem = isProblemDetail(error.response.data) ? error.response.data : null
      const bodyCorrelationId = typeof problem?.correlationId === 'string' ? problem.correlationId : null
      return new ApiError(httpMessage(status, problem), {
        kind: 'http',
        status,
        problem,
        correlationId: headerCorrelationId ?? bodyCorrelationId,
        cause: error,
      })
    }

    if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
      return new ApiError('The server took too long to respond.', {
        kind: 'timeout',
        status: null,
        problem: null,
        correlationId: headerCorrelationId,
        cause: error,
      })
    }

    return new ApiError('Cannot reach the server. Check that the API gateway is running.', {
      kind: 'network',
      status: null,
      problem: null,
      correlationId: headerCorrelationId,
      cause: error,
    })
  }

  const message = error instanceof Error ? error.message : 'Unexpected error.'
  return new ApiError(message, { kind: 'unknown', status: null, problem: null, correlationId: null, cause: error })
}
