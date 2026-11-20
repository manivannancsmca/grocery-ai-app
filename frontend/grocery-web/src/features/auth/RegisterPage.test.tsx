import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../test/mockApi'
import { renderApp } from '../../test/renderApp'
import { fakeAuthResponse } from '../../test/session'

async function fillAccount(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/full name/i), 'Ravi Kumar')
  await user.type(screen.getByLabelText(/^email/i), 'ravi@example.com')
  await user.type(screen.getByLabelText(/^password/i), 'Secret@123')
  await user.type(screen.getByLabelText(/confirm password/i), 'Secret@123')
}

describe('RegisterPage', () => {
  it('registers with the chosen role and lands in that role area', async () => {
    const user = userEvent.setup()
    const api = mockApi({
      'POST /auth/register': (request) => ({
        status: 201,
        data: fakeAuthResponse('access-1', { fullName: 'Ravi Kumar', roles: [(request.body as { role: 'RESTAURANT_OWNER' }).role] }),
      }),
      // The profile is created asynchronously; the dashboard tolerates the brief 404 by retrying.
      'GET /restaurants/me': problem(404, 'RESTAURANT_NOT_FOUND'),
    })
    const { router } = renderApp('/register')

    expect(await screen.findByRole('radio', { name: /customer/i })).toBeChecked()
    await user.click(screen.getByRole('radio', { name: /restaurant owner/i }))
    expect(screen.getByRole('radio', { name: /restaurant owner/i })).toBeChecked()
    await fillAccount(user)
    await user.click(screen.getByRole('button', { name: 'Create restaurant owner account' }))

    expect(await screen.findByText(/setting up your profile/i)).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/restaurant')
    expect(api.callsTo('POST /auth/register')[0]?.body).toEqual({
      role: 'RESTAURANT_OWNER',
      fullName: 'Ravi Kumar',
      email: 'ravi@example.com',
      password: 'Secret@123',
    })
  })

  it('preselects the role from ?role=', async () => {
    renderApp('/register?role=DELIVERY_PARTNER')

    expect(await screen.findByRole('radio', { name: /delivery partner/i })).toBeChecked()
    expect(screen.getByRole('radio', { name: /customer/i })).not.toBeChecked()
  })

  it('validates the password rules and confirmation', async () => {
    const user = userEvent.setup()
    const api = mockApi({})
    renderApp('/register')

    await user.type(await screen.findByLabelText(/^password/i), 'lettersonly')
    await user.type(screen.getByLabelText(/confirm password/i), 'different1')
    await user.click(screen.getByRole('button', { name: /create customer account/i }))

    expect(await screen.findByText('Include at least one letter and one digit.')).toBeInTheDocument()
    expect(screen.getByText('Passwords do not match.')).toBeInTheDocument()
    expect(api.callsTo('POST /auth/register')).toHaveLength(0)
  })

  it('puts EMAIL_ALREADY_REGISTERED on the email field', async () => {
    const user = userEvent.setup()
    mockApi({ 'POST /auth/register': problem(409, 'EMAIL_ALREADY_REGISTERED', 'Email is already registered') })
    renderApp('/register')

    await screen.findByRole('radio', { name: /customer/i })
    await fillAccount(user)
    await user.click(screen.getByRole('button', { name: /create customer account/i }))

    expect(await screen.findByText(/already registered\. sign in instead/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^email/i)).toHaveAttribute('aria-invalid', 'true')
  })

  it('explains what joining involves for the chosen role and keeps the role on the sign-in link', async () => {
    const user = userEvent.setup()
    renderApp('/register')

    const panel = await screen.findByRole('complementary', { name: 'What joining FreshCart involves' })
    expect(panel).toHaveTextContent('Add a delivery address')
    expect(panel).toHaveTextContent('Customer accounts are ready to use as soon as you sign up.')

    await user.click(screen.getByRole('radio', { name: /vendor/i }))
    expect(panel).toHaveTextContent('Run your grocery store from one dashboard.')
    expect(panel).toHaveTextContent('Fill in your store profile')
    expect(panel).toHaveTextContent(/freshcart reviews every store, restaurant and delivery partner/i)
    expect(screen.getAllByRole('link', { name: 'Sign in' }).map((link) => link.getAttribute('href'))).toContain(
      '/login?role=VENDOR',
    )
  })

  it('shows and hides each password field on its own', async () => {
    const user = userEvent.setup()
    renderApp('/register')

    const password = await screen.findByLabelText(/^password/i)
    const confirmation = screen.getByLabelText(/confirm password/i)
    await user.click(screen.getByRole('button', { name: 'Show password' }))
    expect(password).toHaveAttribute('type', 'text')
    expect(confirmation).toHaveAttribute('type', 'password')

    await user.click(screen.getByRole('button', { name: 'Show password confirmation' }))
    expect(confirmation).toHaveAttribute('type', 'text')
  })
})
