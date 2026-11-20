import { AxiosError, AxiosHeaders } from 'axios'
import { httpClient } from '../shared/api/httpClient'

/** Body of a ProblemDetail error reply. */
export function problem(status, errorCode, detail = errorCode, errors) {
  return {
    status,
    data: { type: 'about:blank', title: errorCode, status, detail, errorCode, ...(errors ? { errors } : {}) },
  }
}

function matchRoute(pattern, path) {
  const patternParts = pattern.split('/').filter(Boolean)
  const pathParts = path.split('/').filter(Boolean)
  if (patternParts.length !== pathParts.length) return null
  const params = {}
  for (let index = 0; index < patternParts.length; index++) {
    const expected = patternParts[index] ?? ''
    const actual = pathParts[index] ?? ''
    if (expected.startsWith(':')) params[expected.slice(1)] = decodeURIComponent(actual)
    else if (expected !== actual) return null
  }
  return params
}

/**
 * Replaces the shared axios client's transport with an in-memory router. Keys are `"METHOD /path"`
 * (paths relative to /api, `:param` segments allowed). Unmatched requests answer 501 so a test
 * notices them. Returns the recorded calls.
 */
export function mockApi(routes) {
  const calls = []
  const entries = Object.entries(routes).map(([key, handler]) => {
    const [method = 'GET', pattern = '/'] = key.split(' ')
    return { method: method.toUpperCase(), pattern, handler }
  })

  const adapter = async (config) => {
    const method = (config.method ?? 'get').toUpperCase()
    const path = (config.url ?? '').split('?')[0] ?? ''
    const body = typeof config.data === 'string' && config.data.length > 0 ? JSON.parse(config.data) : config.data
    const headers = AxiosHeaders.from(config.headers)

    let reply = { status: 501, data: { detail: `Unmocked request: ${method} ${path}`, errorCode: 'UNMOCKED' } }
    for (const entry of entries) {
      if (entry.method !== method) continue
      const pathParams = matchRoute(entry.pattern, path)
      if (!pathParams) continue
      const request = {
        method,
        path,
        params: config.params ?? {},
        body,
        headers,
        withCredentials: Boolean(config.withCredentials),
        pathParams,
      }
      calls.push(request)
      reply = typeof entry.handler === 'function' ? await entry.handler(request) : entry.handler
      break
    }

    if (reply.delayMs) await new Promise((resolve) => setTimeout(resolve, reply.delayMs))

    const response = {
      data: reply.data ?? '',
      status: reply.status,
      statusText: String(reply.status),
      headers: new AxiosHeaders({ 'content-type': 'application/json', ...reply.headers }),
      config,
      request: {},
    }
    const validateStatus = config.validateStatus ?? ((status) => status >= 200 && status < 300)
    if (validateStatus(reply.status)) return response
    throw new AxiosError(
      `Request failed with status code ${reply.status}`,
      reply.status >= 500 ? AxiosError.ERR_BAD_RESPONSE : AxiosError.ERR_BAD_REQUEST,
      config,
      {},
      response,
    )
  }

  httpClient.defaults.adapter = adapter

  return {
    calls,
    callsTo(key) {
      const [method = 'GET', pattern = '/'] = key.split(' ')
      return calls.filter((call) => call.method === method.toUpperCase() && matchRoute(pattern, call.path) !== null)
    },
  }
}
