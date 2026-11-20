import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../test/mockApi'
import { renderApp } from '../../test/renderApp'
import { fakeAuthResponse } from '../../test/session'

const draftVendor = {
  id: 'v1',
  ownerUserId: 'user-1',
  status: 'DRAFT',
  businessName: null,
  legalName: null,
  gstin: null,
  fssaiLicense: null,
  contactEmail: null,
  contactPhone: null,
  address: null,
  statusReason: null,
  submittedAt: null,
  approvedAt: null,
  createdAt: '2026-11-11T10:00:00Z',
  updatedAt: '2026-11-11T10:00:00Z',
}

describe('LoginPage', () => {
  it('validates the form before calling the API', async () => {
    const user = userEvent.setup()
    const api = mockApi({})
    renderApp('/login')

    await user.click(await screen.findByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Enter your email address.')).toBeInTheDocument()
    expect(screen.getByText('Enter your password.')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true')

    await user.type(screen.getByLabelText('Email'), 'not-an-email')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Enter a valid email address.')).toBeInTheDocument()

    expect(api.callsTo('POST /auth/login')).toHaveLength(0)
  })

  it('shows the server error for wrong credentials', async () => {
    const user = userEvent.setup()
    const api = mockApi({ 'POST /auth/login': problem(401, 'INVALID_CREDENTIALS', 'Invalid email or password') })
    renderApp('/login')

    await user.type(await screen.findByLabelText('Email'), 'asha@example.com')
    await user.type(screen.getByLabelText('Password'), 'Wrong@123')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/incorrect email or password/i)
    expect(api.callsTo('POST /auth/login')[0]?.body).toEqual({ email: 'asha@example.com', password: 'Wrong@123' })
    expect(api.callsTo('POST /auth/login')[0]?.withCredentials).toBe(true)
  })

  it('signs in and opens the role home page', async () => {
    const user = userEvent.setup()
    mockApi({
      'POST /auth/login': { status: 200, data: fakeAuthResponse('access-1', { roles: ['VENDOR'] }) },
      'GET /vendors/me': { status: 200, data: draftVendor },
    })
    const { router } = renderApp('/login')

    await user.type(await screen.findByLabelText('Email'), 'asha@example.com')
    await user.type(screen.getByLabelText('Password'), 'Secret@123')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Set up your store' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/vendor')
    expect(screen.getByRole('button', { name: /account menu for asha rao/i })).toBeInTheDocument()
  })

  it('shows the suspended screen for 403 ACCOUNT_SUSPENDED', async () => {
    const user = userEvent.setup()
    mockApi({ 'POST /auth/login': problem(403, 'ACCOUNT_SUSPENDED', 'Suspended for repeated order fraud') })
    const { router } = renderApp('/login')

    await user.type(await screen.findByLabelText('Email'), 'asha@example.com')
    await user.type(screen.getByLabelText('Password'), 'Secret@123')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Account suspended' })).toBeInTheDocument()
    expect(screen.getByText('Suspended for repeated order fraud')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/account-suspended')
  })

  it('offers a view for every stakeholder and tailors the screen to the chosen one', async () => {
    const user = userEvent.setup()
    renderApp('/login')

    const tabs = await screen.findAllByRole('tab')
    expect(tabs.map((tab) => tab.textContent)).toEqual(['Customer', 'Vendor', 'Restaurant', 'Delivery', 'Admin'])
    expect(screen.getByRole('tab', { name: 'Customer' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('link', { name: 'Create a customer account' })).toHaveAttribute('href', '/register?role=CUSTOMER')

    await user.click(screen.getByRole('tab', { name: 'Vendor' }))
    expect(screen.getByRole('tab', { name: 'Vendor' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel', { name: 'Vendor' })).toHaveTextContent('Run your grocery store from one dashboard.')
    expect(screen.getByRole('link', { name: 'Create a vendor account' })).toHaveAttribute('href', '/register?role=VENDOR')

    // Arrow keys move between the tabs.
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Restaurant' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Restaurant' })).toHaveFocus()

    // Admin accounts are not self-service.
    await user.click(screen.getByRole('tab', { name: 'Admin' }))
    expect(screen.queryByRole('link', { name: /create a \w.* account/i })).not.toBeInTheDocument()
    expect(screen.getByText(/admin accounts are created by a freshcart super admin/i)).toBeInTheDocument()
  })

  it('opens on the stakeholder named in ?role=', async () => {
    renderApp('/login?role=DELIVERY_PARTNER')

    expect(await screen.findByRole('tab', { name: 'Delivery' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('link', { name: 'Create a delivery partner account' })).toBeInTheDocument()
  })

  it('opens on the stakeholder whose page the visitor was sent here from', async () => {
    const { router } = renderApp('/restaurant/menu')

    expect(await screen.findByRole('tab', { name: 'Restaurant' })).toHaveAttribute('aria-selected', 'true')
    expect(router.state.location.pathname).toBe('/login')
  })

  it('lists the dev accounts of the chosen stakeholder and signs in with the one picked', async () => {
    const user = userEvent.setup()
    const api = mockApi({
      'POST /auth/login': { status: 200, data: fakeAuthResponse('access-1', { roles: ['VENDOR'] }) },
      'GET /vendors/me': { status: 200, data: draftVendor },
    })
    const { router } = renderApp('/login')

    // Customer tab: the customer account only.
    expect(await screen.findByRole('heading', { name: 'Customer dev account' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /dev\.customer@grocery\.local/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /dev\.vendor@grocery\.local/ })).not.toBeInTheDocument()

    // Admin tab: the super admin and one account per admin role.
    await user.click(screen.getByRole('tab', { name: 'Admin' }))
    expect(screen.getByRole('heading', { name: 'Admin dev accounts' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /super admin.*admin@grocery\.local/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /finance admin.*dev\.finance@grocery\.local/i })).toBeInTheDocument()

    // Vendor tab: picking the account fills the form; "Sign in" sends it.
    await user.click(screen.getByRole('tab', { name: 'Vendor' }))
    await user.click(screen.getByRole('button', { name: /dev\.vendor@grocery\.local/ }))
    expect(screen.getByLabelText('Email')).toHaveValue('dev.vendor@grocery.local')
    expect(screen.getByLabelText('Password')).toHaveValue('Dev@12345')
    expect(api.callsTo('POST /auth/login')).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('heading', { name: 'Set up your store' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/vendor')
    expect(api.callsTo('POST /auth/login')[0]?.body).toEqual({ email: 'dev.vendor@grocery.local', password: 'Dev@12345' })
  })

  it('shows and hides the password', async () => {
    const user = userEvent.setup()
    renderApp('/login')

    const password = await screen.findByLabelText('Password')
    await user.type(password, 'Secret@123')
    expect(password).toHaveAttribute('type', 'password')

    await user.click(screen.getByRole('button', { name: 'Show password' }))
    expect(password).toHaveAttribute('type', 'text')
    expect(password).toHaveValue('Secret@123')

    await user.click(screen.getByRole('button', { name: 'Hide password' }))
    expect(password).toHaveAttribute('type', 'password')
  })
})
