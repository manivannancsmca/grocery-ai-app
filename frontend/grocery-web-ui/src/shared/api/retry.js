import { ApiError, hasErrorCode } from './problemDetail'

const MAX_RETRIES = 2

/**
 * Retry only failures that can succeed on a second try: network problems, timeouts and 5xx.
 * @param {number} failureCount
 * @param {unknown} error
 * @returns {boolean}
 */
export function shouldRetry(failureCount, error) {
  if (failureCount >= MAX_RETRIES) return false
  if (error instanceof ApiError) {
    return error.kind !== 'http' || (error.status !== null && error.status >= 500)
  }
  return false
}

/** How long to keep retrying a `404 …_NOT_FOUND` right after registration (profiles appear ~1 s later). */
export const NOT_FOUND_RETRY_ATTEMPTS = 6
export const NOT_FOUND_RETRY_DELAY_MS = 1_000

/**
 * Retry policy for "my profile" queries. Right after registration the profile is created
 * asynchronously (UserRegistered event), so `404 <notFoundCode>` is retried for a few seconds.
 * @param {string} notFoundCode
 */
export function retryWhileProvisioning(notFoundCode) {
  return (failureCount, error) => {
    if (hasErrorCode(error, notFoundCode)) return failureCount < NOT_FOUND_RETRY_ATTEMPTS
    return shouldRetry(failureCount, error)
  }
}

/**
 * @param {number} failureCount
 * @param {Error} error
 * @returns {number}
 */
export function provisioningRetryDelay(failureCount, error) {
  if (error instanceof ApiError && error.status === 404) return NOT_FOUND_RETRY_DELAY_MS
  return Math.min(1_000 * 2 ** failureCount, 10_000)
}
