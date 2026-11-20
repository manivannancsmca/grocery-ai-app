import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../test/mockApi'
import { fakeAuthResponse, signInAs } from '../../test/session'
import { getAccessToken, sessionStore } from '../auth/session'
import { httpClient } from './httpClient'
import { ApiError } from './problemDetail'

/** Protected endpoint that only accepts the token issued by the mocked refresh. */
function protectedThing(validToken = 'fresh-token') {
  return (request) =>
    request.headers.get('Authorization') === `Bearer ${validToken}`
      ? { status: 200, data: { id: request.pathParams.id } }
      : problem(401, 'TOKEN_EXPIRED', 'Access token expired')
}

describe('httpClient auth interceptor', () => {
  it('attaches the in-memory access token as a Bearer header', async () => {
    signInAs(['CUSTOMER'], {}, 'token-123')
    const api = mockApi({ 'GET /customers/me': { status: 200, data: {} } })

    await httpClient.get('/customers/me')

    expect(api.calls[0]?.headers.get('Authorization')).toBe('Bearer token-123')
  })

  it('runs ONE shared refresh for concurrent 401s and retries each request once with the new token', async () => {
    signInAs(['CUSTOMER'], {}, 'expired-token')
    const api = mockApi({
      'GET /things/:id': protectedThing(),
      'POST /auth/refresh': { status: 200, data: fakeAuthResponse('fresh-token'), delayMs: 20 },
    })

    const responses = await Promise.all(['a', 'b', 'c'].map((id) => httpClient.get(`/things/${id}`)))

    expect(responses.map((response) => response.data.id)).toEqual(['a', 'b', 'c'])
    expect(api.callsTo('POST /auth/refresh')).toHaveLength(1)
    // 3 failed originals + 3 retries
    expect(api.callsTo('GET /things/:id')).toHaveLength(6)
    expect(getAccessToken()).toBe('fresh-token')

    const refresh = api.callsTo('POST /auth/refresh')[0]
    expect(refresh?.withCredentials).toBe(true)
    expect(refresh?.headers.get('X-Requested-With')).toBe('XMLHttpRequest')
    expect(refresh?.headers.get('Authorization')).toBeFalsy()
  })

  it('retries the original request only once', async () => {
    signInAs(['CUSTOMER'], {}, 'expired-token')
    const api = mockApi({
      'GET /things/:id': protectedThing('a-token-the-server-never-accepts'),
      'POST /auth/refresh': { status: 200, data: fakeAuthResponse('fresh-token') },
    })

    await expect(httpClient.get('/things/x')).rejects.toMatchObject({ status: 401 })
    expect(api.callsTo('POST /auth/refresh')).toHaveLength(1)
    expect(api.callsTo('GET /things/:id')).toHaveLength(2)
  })

  it('retries the refresh once after 409 REFRESH_RACE', async () => {
    signInAs(['CUSTOMER'], {}, 'expired-token')
    let refreshCalls = 0
    const api = mockApi({
      'GET /things/:id': protectedThing(),
      'POST /auth/refresh': () => {
        refreshCalls += 1
        return refreshCalls === 1
          ? problem(409, 'REFRESH_RACE')
          : { status: 200, data: fakeAuthResponse('fresh-token') }
      },
    })

    const response = await httpClient.get('/things/r')

    expect(response.data.id).toBe('r')
    expect(api.callsTo('POST /auth/refresh')).toHaveLength(2)
  })

  it('ends the session (→ login) when the refresh fails', async () => {
    signInAs(['VENDOR'], {}, 'expired-token')
    mockApi({
      'GET /things/:id': protectedThing(),
      'POST /auth/refresh': problem(401, 'REFRESH_TOKEN_INVALID'),
    })

    const failure = httpClient.get('/things/z')

    await expect(failure).rejects.toBeInstanceOf(ApiError)
    await expect(failure).rejects.toMatchObject({ status: 401 })
    expect(getAccessToken()).toBeNull()
    expect(sessionStore.getSnapshot()).toMatchObject({ status: 'anonymous', user: null, endReason: 'expired' })
  })

  it('switches to the suspended state on 403 ACCOUNT_SUSPENDED during refresh', async () => {
    signInAs(['CUSTOMER'], {}, 'revoked-token')
    mockApi({
      'GET /things/:id': problem(401, 'TOKEN_REVOKED'),
      'POST /auth/refresh': problem(403, 'ACCOUNT_SUSPENDED', 'Your account was suspended: fraud check'),
    })

    await expect(httpClient.get('/things/s')).rejects.toMatchObject({ status: 401 })
    expect(sessionStore.getSnapshot()).toMatchObject({
      status: 'suspended',
      suspendedMessage: 'Your account was suspended: fraud check',
    })
  })

  it('does not try to refresh for a request sent while signed out', async () => {
    const api = mockApi({
      'GET /things/:id': problem(401, 'UNAUTHENTICATED'),
      'POST /auth/refresh': { status: 200, data: fakeAuthResponse('fresh-token') },
    })

    await expect(httpClient.get('/things/anon')).rejects.toMatchObject({ status: 401 })
    expect(api.callsTo('POST /auth/refresh')).toHaveLength(0)
  })

  it('never sends the token to public endpoints and never refreshes for them', async () => {
    signInAs(['CUSTOMER'], {}, 'token-123')
    const api = mockApi({ 'GET /health/identity': { status: 200, data: { status: 'UP' } } })

    await httpClient.get('/health/identity', { skipAuth: true })

    expect(api.calls[0]?.headers.get('Authorization')).toBeFalsy()
  })
})
