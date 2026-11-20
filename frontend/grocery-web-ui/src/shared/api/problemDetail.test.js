import { AxiosError, AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'
import { ApiError, hasErrorCode, problemNumber, problemString, toApiError } from './problemDetail'

function requestConfig(correlationId) {
  return { headers: new AxiosHeaders({ 'X-Correlation-Id': correlationId }) }
}

describe('toApiError', () => {
  it('uses the ProblemDetail detail as the message', () => {
    const config = requestConfig('req-1')
    const error = new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, null, {
      status: 409,
      statusText: 'Conflict',
      headers: {},
      config,
      data: { type: 'about:blank', title: 'Conflict', status: 409, detail: 'Email is already registered.' },
    })

    const apiError = toApiError(error)

    expect(apiError).toBeInstanceOf(ApiError)
    expect(apiError.kind).toBe('http')
    expect(apiError.status).toBe(409)
    expect(apiError.message).toBe('Email is already registered.')
    expect(apiError.problem?.title).toBe('Conflict')
    expect(apiError.correlationId).toBe('req-1')
  })

  it('exposes the contract errorCode and field errors', () => {
    const config = requestConfig('req-4')
    const error = new AxiosError('Unprocessable', 'ERR_BAD_REQUEST', config, null, {
      status: 422,
      statusText: 'Unprocessable Entity',
      headers: {},
      config,
      data: {
        title: 'Unprocessable Entity',
        status: 422,
        detail: 'Profile is incomplete',
        errorCode: 'PROFILE_INCOMPLETE',
        errors: [{ field: 'address.latitude', message: 'must not be null' }],
      },
    })

    const apiError = toApiError(error)

    expect(apiError.errorCode).toBe('PROFILE_INCOMPLETE')
    expect(apiError.fieldErrors).toEqual([{ field: 'address.latitude', message: 'must not be null' }])
    expect(hasErrorCode(apiError, 'PROFILE_INCOMPLETE', 'VALIDATION_FAILED')).toBe(true)
    expect(hasErrorCode(apiError, 'VALIDATION_FAILED')).toBe(false)
  })

  it('describes a missing server as a network error', () => {
    const error = new AxiosError('Network Error', 'ERR_NETWORK', requestConfig('req-2'))

    const apiError = toApiError(error)

    expect(apiError.kind).toBe('network')
    expect(apiError.status).toBeNull()
    expect(apiError.message).toMatch(/cannot reach the server/i)
    expect(apiError.correlationId).toBe('req-2')
  })

  it('falls back to a generic message for non-problem bodies', () => {
    const config = requestConfig('req-3')
    const error = new AxiosError('Bad gateway', 'ERR_BAD_RESPONSE', config, null, {
      status: 502,
      statusText: 'Bad Gateway',
      headers: {},
      config,
      data: '<html>bad gateway</html>',
    })

    expect(toApiError(error).message).toBe('The service is temporarily unavailable (HTTP 502).')
  })
})

describe('problem extension properties', () => {
  function apiError(problem) {
    return new ApiError('Conflict', { kind: 'http', status: 409, problem, correlationId: null })
  }

  it('reads numbers and strings at the top level or under `properties`', () => {
    const top = apiError({ errorCode: 'INSUFFICIENT_STOCK', available: 10, reserved: 2, productId: 'p2' })
    expect(problemNumber(top, 'available')).toBe(10)
    expect(problemString(top, 'productId')).toBe('p2')

    const nested = apiError({ errorCode: 'STOCK_BELOW_RESERVED', properties: { reserved: 4, productId: 'p3' } })
    expect(problemNumber(nested, 'reserved')).toBe(4)
    expect(problemString(nested, 'productId')).toBe('p3')
  })

  it('returns null for missing, mistyped or non-API errors', () => {
    const error = apiError({ available: '10', productId: 7 })
    expect(problemNumber(error, 'available')).toBeNull()
    expect(problemString(error, 'productId')).toBeNull()
    expect(problemString(new Error('boom'), 'productId')).toBeNull()
  })
})
