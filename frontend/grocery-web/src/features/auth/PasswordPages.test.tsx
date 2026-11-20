import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../test/mockApi'
import { renderApp } from '../../test/renderApp'
import { fakeAuthResponse, signInAs } from '../../test/session'

const CONFIRMATION = "If an account exists for that address, we've emailed a link to reset your password."

describe('Forgot password', () => {
  it('is linked from the sign-in page', async () => {
    const user = userEvent.setup()
    const { router } = renderApp('/login')

    await user.click(await screen.findByRole('link', { name: 'Forgot password?' }))

    expect(await screen.findByRole('heading', { name: 'Forgot your password?' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/forgot-password')
  })

  it('checks the address, sends it without a session and always gives the same answer', async () => {
    const user = userEvent.setup()
    const api = mockApi({ 'POST /auth/password/forgot': { status: 202 } })
    renderApp('/forgot-password')

    const email = await screen.findByLabelText('Email')
    await user.type(email, 'not-an-email')
    await user.click(screen.getByRole('button', { name: 'Email me a reset link' }))
    expect(await screen.findByText('Enter a valid email address.')).toBeInTheDocument()
    expect(api.callsTo('POST /auth/password/forgot')).toHaveLength(0)

    await user.clear(email)
    await user.type(email, ' kavya@example.com ')
    await user.click(screen.getByRole('button', { name: 'Email me a reset link' }))

    expect(await screen.findByRole('status')).toHaveTextContent(CONFIRMATION)
    expect(screen.getByRole('heading', { name: 'Check your email' })).toBeInTheDocument()
    const call = api.callsTo('POST /auth/password/forgot')[0]
    expect(call?.body).toEqual({ email: 'kavya@example.com' })
    expect(call?.headers.get('Authorization')).toBeFalsy()
    expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/login')
  })

  it('asks to wait when the gateway limits the requests', async () => {
    const user = userEvent.setup()
    mockApi({ 'POST /auth/password/forgot': problem(429, 'TOO_MANY_REQUESTS') })
    renderApp('/forgot-password')

    await user.type(await screen.findByLabelText('Email'), 'kavya@example.com')
    await user.click(screen.getByRole('button', { name: 'Email me a reset link' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Wait a minute and try again.')
  })
})

describe('Reset password', () => {
  it('sets the new password with the token of the link, then sends the user to sign in', async () => {
    const user = userEvent.setup()
    const api = mockApi({ 'POST /auth/password/reset': { status: 204 } })
    renderApp('/reset-password?token=abc123_-XYZ')

    await user.type(await screen.findByLabelText(/^new password/i), 'Fresh2026x')
    await user.type(screen.getByLabelText(/^confirm new password/i), 'Fresh2026x')
    await user.click(screen.getByRole('button', { name: 'Set new password' }))

    expect(await screen.findByRole('heading', { name: 'Password changed' })).toBeInTheDocument()
    expect(api.callsTo('POST /auth/password/reset')[0]?.body).toEqual({ token: 'abc123_-XYZ', newPassword: 'Fresh2026x' })
    expect(screen.getByRole('link', { name: /sign in with your new password/i })).toHaveAttribute('href', '/login')
  })

  it('follows the registration rules before calling the API', async () => {
    const user = userEvent.setup()
    const api = mockApi({})
    renderApp('/reset-password?token=abc')

    await user.type(await screen.findByLabelText(/^new password/i), 'short1')
    await user.type(screen.getByLabelText(/^confirm new password/i), 'short2')
    await user.click(screen.getByRole('button', { name: 'Set new password' }))
    expect(await screen.findByText('Use 8 to 64 characters.')).toBeInTheDocument()

    await user.clear(screen.getByLabelText(/^new password/i))
    await user.type(screen.getByLabelText(/^new password/i), 'lettersonly')
    await user.click(screen.getByRole('button', { name: 'Set new password' }))
    expect(await screen.findByText('Include at least one letter and one digit.')).toBeInTheDocument()

    await user.clear(screen.getByLabelText(/^new password/i))
    await user.type(screen.getByLabelText(/^new password/i), 'Fresh2026x')
    await user.click(screen.getByRole('button', { name: 'Set new password' }))
    expect(await screen.findByText('Passwords do not match.')).toBeInTheDocument()

    expect(api.callsTo('POST /auth/password/reset')).toHaveLength(0)
  })

  it('says when the link is invalid, expired or used, and offers a new one', async () => {
    const user = userEvent.setup()
    mockApi({ 'POST /auth/password/reset': problem(400, 'RESET_TOKEN_INVALID') })
    renderApp('/reset-password?token=used-one')

    await user.type(await screen.findByLabelText(/^new password/i), 'Fresh2026x')
    await user.type(screen.getByLabelText(/^confirm new password/i), 'Fresh2026x')
    await user.click(screen.getByRole('button', { name: 'Set new password' }))

    expect(await screen.findByRole('heading', { name: "This link doesn't work" })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('invalid or has expired, or it was already used')
    expect(screen.getByRole('link', { name: 'Ask for a new link' })).toHaveAttribute('href', '/forgot-password')
  })

  it('a link without a token is invalid straight away', async () => {
    renderApp('/reset-password')

    expect(await screen.findByRole('heading', { name: "This link doesn't work" })).toBeInTheDocument()
    expect(screen.queryByLabelText(/^new password/i)).not.toBeInTheDocument()
  })
})

describe('Change password', () => {
  it('is in the account menu of every role', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'])
    renderApp('/account/password')

    expect(await screen.findByRole('heading', { name: 'Change password', level: 1 })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /account menu for asha rao/i }))
    expect(screen.getByRole('link', { name: 'Change password' })).toHaveAttribute('href', '/account/password')
  })

  it('opens for a customer too, next to the other account pages of customers', async () => {
    signInAs(['CUSTOMER'])
    const { router } = renderApp('/account/password')

    expect(await screen.findByRole('heading', { name: 'Change password', level: 1 })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/account/password')
  })

  it('refuses a wrong current password, then changes it, keeps this session and says other devices were signed out', async () => {
    const user = userEvent.setup()
    signInAs(['DELIVERY_PARTNER'])
    let attempts = 0
    const api = mockApi({
      'POST /auth/password/change': () => {
        attempts += 1
        return attempts === 1 ? problem(400, 'CURRENT_PASSWORD_INCORRECT') : { status: 204 }
      },
      'POST /auth/refresh': { status: 200, data: fakeAuthResponse('renewed-token', { roles: ['DELIVERY_PARTNER'] }) },
    })
    renderApp('/account/password')

    expect(await screen.findByText('Changing it signs you out on your other devices. You stay signed in here.')).toBeInTheDocument()
    await user.type(screen.getByLabelText(/^current password/i), 'Wrong1234')
    await user.type(screen.getByLabelText(/^new password/i), 'Fresh2026x')
    await user.type(screen.getByLabelText(/^confirm new password/i), 'Fresh2026x')
    await user.click(screen.getByRole('button', { name: 'Change password' }))

    expect(await screen.findByText(/This is not your current password/)).toBeInTheDocument()
    expect(api.callsTo('POST /auth/refresh')).toHaveLength(0)

    await user.clear(screen.getByLabelText(/^current password/i))
    await user.type(screen.getByLabelText(/^current password/i), 'Secret@123')
    await user.click(screen.getByRole('button', { name: 'Change password' }))

    expect(await screen.findByText('Password changed')).toBeInTheDocument()
    expect(screen.getByText(/Your other devices were signed out./)).toBeInTheDocument()
    const change = api.callsTo('POST /auth/password/change')[1]
    expect(change?.body).toEqual({ currentPassword: 'Secret@123', newPassword: 'Fresh2026x' })
    expect(change?.headers.get('Authorization')).toBe('Bearer test-access-token')
    expect(change?.withCredentials).toBe(true)
    // identity revoked the access tokens issued so far: this session renews its own at once
    expect(api.callsTo('POST /auth/refresh')).toHaveLength(1)
    expect(screen.getByLabelText(/^current password/i)).toHaveValue('')
  })

  it('wants a new password that differs from the current one and matches its confirmation', async () => {
    const user = userEvent.setup()
    signInAs(['SUPPORT'])
    const api = mockApi({})
    renderApp('/account/password')

    await user.type(await screen.findByLabelText(/^current password/i), 'Secret@123')
    await user.type(screen.getByLabelText(/^new password/i), 'Secret@123')
    await user.type(screen.getByLabelText(/^confirm new password/i), 'Other@1234')
    await user.click(screen.getByRole('button', { name: 'Change password' }))

    expect(await screen.findByText('Choose a password different from your current one.')).toBeInTheDocument()
    expect(screen.getByText('Passwords do not match.')).toBeInTheDocument()
    expect(api.callsTo('POST /auth/password/change')).toHaveLength(0)
  })
})
