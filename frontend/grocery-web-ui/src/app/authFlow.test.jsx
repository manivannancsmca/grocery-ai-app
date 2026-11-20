import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { resetSessionForTests } from '../shared/auth/session'
import { mockApi, problem } from '../test/mockApi'
import { renderApp } from '../test/renderApp'
import { fakeAuthResponse, signInAs } from '../test/session'

const partner = {
  id: 'user-1',
  email: 'asha@example.com',
  status: 'DRAFT',
  fullName: 'Asha Rao',
  phone: '+919876543210',
  city: null,
  vehicleType: null,
  vehicleNumber: null,
  drivingLicenseNumber: null,
  statusReason: null,
  submittedAt: null,
  approvedAt: null,
  createdAt: '2026-11-11T10:00:00Z',
  updatedAt: '2026-11-11T10:00:00Z',
}

describe('session lifecycle', () => {
  it('restores the session silently on start from the refresh cookie', async () => {
    resetSessionForTests('initializing')
    const api = mockApi({
      'POST /auth/refresh': { status: 200, data: fakeAuthResponse('restored', { roles: ['DELIVERY_PARTNER'] }) },
      'GET /delivery/partners/me': { status: 200, data: partner },
    })
    renderApp('/delivery/profile')

    expect(await screen.findByRole('heading', { name: 'Partner profile' })).toBeInTheDocument()
    expect(api.callsTo('POST /auth/refresh')).toHaveLength(1)
    // The heading renders before the profile query fires: wait for the request (flaked on a loaded CI runner).
    await waitFor(() =>
      expect(api.callsTo('GET /delivery/partners/me')[0]?.headers.get('Authorization')).toBe('Bearer restored'),
    )
  })

  it('logs out when the refresh fails, then returns to the intended page after signing in', async () => {
    const user = userEvent.setup()
    signInAs(['DELIVERY_PARTNER'], {}, 'expired-token')
    mockApi({
      'GET /delivery/partners/me': (request) =>
        request.headers.get('Authorization') === 'Bearer new-token'
          ? { status: 200, data: partner }
          : problem(401, 'TOKEN_EXPIRED'),
      'POST /auth/refresh': problem(401, 'REFRESH_TOKEN_INVALID'),
      'POST /auth/login': { status: 200, data: fakeAuthResponse('new-token', { roles: ['DELIVERY_PARTNER'] }) },
    })
    const { router } = renderApp('/delivery/profile')

    expect(await screen.findByText('Your session has expired. Please sign in again.')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.state).toEqual({ from: '/delivery/profile' })

    await user.type(screen.getByLabelText('Email'), 'asha@example.com')
    await user.type(screen.getByLabelText('Password'), 'Secret@123')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Partner profile' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/delivery/profile')
  })

  it('signs out from the user menu', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    const api = mockApi({
      'POST /auth/logout': { status: 204 },
      'GET /health/identity': { status: 200, data: { status: 'UP' } },
    })
    const { router } = renderApp('/')

    await user.click(await screen.findByRole('button', { name: /account menu for asha rao/i }))
    expect(screen.getByText('asha@example.com')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    const logout = api.callsTo('POST /auth/logout')[0]
    expect(logout?.withCredentials).toBe(true)
    expect(logout?.headers.get('X-Requested-With')).toBe('XMLHttpRequest')
  })
})
