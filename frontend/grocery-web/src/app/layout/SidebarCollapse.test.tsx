import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import { mockApi } from '../../test/mockApi'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'
import { RoleNav } from './RoleNav'

afterEach(() => {
  try {
    window.localStorage.removeItem('freshcart.sidebar')
  } catch {
    // ignore
  }
})

describe('Sidebar — collapsed menu', () => {
  it('keeps every link and its name, hides the labels and shows the name in a tooltip on hover and focus', () => {
    render(
      <MemoryRouter initialEntries={['/admin/orders']}>
        <RoleNav area="ADMIN" roles={['SUPER_ADMIN']} collapsed />
      </MemoryRouter>,
    )
    const nav = screen.getByRole('navigation', { name: 'Main navigation' })
    const orders = within(nav).getByRole('link', { name: 'Orders' })
    expect(orders).toHaveAttribute('href', '/admin/orders')
    expect(orders).toHaveAttribute('aria-current', 'page')
    expect(within(orders).getByText('Orders')).toHaveClass('sr-only')

    fireEvent.mouseEnter(orders)
    expect(document.body.querySelector('[aria-hidden="true"].fixed')).toHaveTextContent('Orders')
    fireEvent.mouseLeave(orders)
    expect(document.body.querySelector('[aria-hidden="true"].fixed')).toBeNull()

    const payments = within(nav).getByRole('link', { name: 'Payments' })
    fireEvent.focus(payments)
    expect(document.body.querySelector('[aria-hidden="true"].fixed')).toHaveTextContent('Payments')
  })

  it('shows no tooltip while the menu is expanded', () => {
    render(
      <MemoryRouter>
        <RoleNav area="ADMIN" roles={['SUPER_ADMIN']} />
      </MemoryRouter>,
    )
    const orders = screen.getByRole('link', { name: 'Orders' })
    expect(within(orders).getByText('Orders')).not.toHaveClass('sr-only')
    fireEvent.mouseEnter(orders)
    expect(document.body.querySelector('[aria-hidden="true"].fixed')).toBeNull()
  })

  it('collapses and expands with the toggle, keeps the choice while navigating and remembers it', async () => {
    const user = userEvent.setup()
    signInAs(['SUPER_ADMIN'])
    mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /admin/orders': { status: 200, data: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } },
      'GET /admin/payments': { status: 200, data: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } },
    })
    renderApp('/admin/orders')

    const collapse = await screen.findByRole('button', { name: 'Collapse menu' })
    expect(collapse).toHaveAttribute('aria-expanded', 'true')
    await user.click(collapse)
    const expand = screen.getByRole('button', { name: 'Expand menu' })
    expect(expand).toHaveAttribute('aria-expanded', 'false')
    expect(window.localStorage.getItem('freshcart.sidebar')).toBe('collapsed')

    // Still collapsed on the next page.
    const desktopNav = document.getElementById('desktop-navigation')
    expect(desktopNav).not.toBeNull()
    await user.click(within(desktopNav as HTMLElement).getByRole('link', { name: 'Payments' }))
    expect(await screen.findByRole('button', { name: 'Expand menu' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Expand menu' }))
    expect(screen.getByRole('button', { name: 'Collapse menu' })).toBeInTheDocument()
    expect(window.localStorage.getItem('freshcart.sidebar')).toBe('expanded')
  })
})
