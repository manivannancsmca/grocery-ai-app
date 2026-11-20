import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { mockApi } from '../test/mockApi'
import { renderApp } from '../test/renderApp'
import { signInAs } from '../test/session'

// Keep the health widget off the network.
vi.mock('../features/system/api', async (importOriginal) => ({
  ...(await importOriginal()),
  fetchIdentityHealth: vi.fn().mockResolvedValue({ status: 'UP' }),
}))

const emptyPage = { status: 200, data: { content: [], page: 0, size: 1, totalElements: 0, totalPages: 0 } }

describe('router', () => {
  it('renders the 404 page for unknown routes', async () => {
    renderApp('/this/route/does-not-exist')
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to the storefront' })).toHaveAttribute('href', '/')
  })

  it('shows the public storefront to signed-out visitors', async () => {
    renderApp('/')
    expect(await screen.findByRole('heading', { name: /fresh groceries and restaurant meals/i })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Create account' }).length).toBeGreaterThan(0)
  })

  it('lazy-loads the admin dashboard for an admin', async () => {
    mockApi({
      'GET /admin/vendors': emptyPage,
      'GET /admin/restaurants': emptyPage,
      'GET /admin/delivery-partners': emptyPage,
    })
    signInAs(['SUPER_ADMIN'])
    renderApp('/admin')
    expect(await screen.findByRole('heading', { name: 'Admin dashboard' })).toBeInTheDocument()
  })

  it('sends signed-in non-customers from the storefront to their own home', async () => {
    mockApi({
      'GET /admin/vendors': emptyPage,
      'GET /admin/restaurants': emptyPage,
      'GET /admin/delivery-partners': emptyPage,
    })
    signInAs(['OPS_ADMIN'])
    const { router } = renderApp('/')
    expect(await screen.findByRole('heading', { name: 'Admin dashboard' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/admin')
  })
})
