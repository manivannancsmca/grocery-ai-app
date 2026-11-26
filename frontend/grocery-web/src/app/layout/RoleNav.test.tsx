import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { RoleArea, UserRole } from '../../shared/auth/roles'
import { RoleNav } from './RoleNav'

function renderNav(area: RoleArea, roles: UserRole[]) {
  render(
    <MemoryRouter>
      <RoleNav area={area} roles={roles} />
    </MemoryRouter>,
  )
  return screen.getByRole('navigation', { name: 'Main navigation' })
}

describe('RoleNav', () => {
  it('shows vendor links, including the Phase 8 order board and reports', () => {
    const nav = renderNav('VENDOR', ['VENDOR'])

    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toHaveAttribute('href', '/vendor')
    expect(within(nav).getByRole('link', { name: 'Business profile' })).toHaveAttribute('href', '/vendor/profile')
    expect(within(nav).getByRole('link', { name: 'Products' })).toHaveAttribute('href', '/vendor/products')

    expect(within(nav).getByRole('link', { name: 'Inventory' })).toHaveAttribute('href', '/vendor/inventory')
    expect(within(nav).getByRole('link', { name: 'Pricing' })).toHaveAttribute('href', '/vendor/pricing')
    expect(within(nav).getByRole('link', { name: 'Orders' })).toHaveAttribute('href', '/vendor/orders')
    expect(within(nav).getByRole('link', { name: 'Reports' })).toHaveAttribute('href', '/vendor/reports')

    expect(within(nav).queryByText('Approvals')).not.toBeInTheDocument()
  })

  it('shows the restaurant order board and reports', () => {
    const nav = renderNav('RESTAURANT_OWNER', ['RESTAURANT_OWNER'])

    expect(within(nav).getByRole('link', { name: 'Menu' })).toHaveAttribute('href', '/restaurant/menu')
    expect(within(nav).getByRole('link', { name: 'Orders' })).toHaveAttribute('href', '/restaurant/orders')
    expect(within(nav).getByRole('link', { name: 'Reports' })).toHaveAttribute('href', '/restaurant/reports')
  })

  it('links the customer to what the Phase 11 assistant prepared (no unbuilt screens left)', () => {
    const nav = renderNav('CUSTOMER', ['CUSTOMER'])

    expect(within(nav).getByRole('link', { name: 'Assistant actions' })).toHaveAttribute('href', '/assistant/approvals')
    expect(within(nav).queryByText(/Phase \d+/)).not.toBeInTheDocument()
    expect(nav.querySelector('[aria-disabled="true"]')).toBeNull()
  })

  it('shows the Phase 11 AI approvals and AI audit pages to every admin role, next to onboarding Approvals', () => {
    for (const role of ['SUPER_ADMIN', 'OPS_ADMIN', 'FINANCE_ADMIN', 'SUPPORT', 'DELIVERY_MANAGER'] as const) {
      const nav = renderNav('ADMIN', [role])
      expect(within(nav).getByRole('link', { name: 'AI approvals' })).toHaveAttribute('href', '/admin/ai/approvals')
      expect(within(nav).getByRole('link', { name: 'AI audit' })).toHaveAttribute('href', '/admin/ai/audit')
      expect(within(nav).getByRole('link', { name: 'Help center' })).toHaveAttribute('href', '/admin/ai/knowledge')
      expect(within(nav).getByRole('link', { name: 'Approvals' })).toHaveAttribute('href', '/admin/approvals')
      cleanup()
    }
  })

  it('shows the Phase 9 delivery partner pages (offers live on the dashboard)', () => {
    const nav = renderNav('DELIVERY_PARTNER', ['DELIVERY_PARTNER'])

    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toHaveAttribute('href', '/delivery')
    expect(within(nav).getByRole('link', { name: 'History' })).toHaveAttribute('href', '/delivery/history')
    expect(within(nav).queryByText(/Phase 9/)).not.toBeInTheDocument()
  })

  it('shows admin pages, including Users, to an operations admin', () => {
    const nav = renderNav('ADMIN', ['OPS_ADMIN'])

    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toHaveAttribute('href', '/admin')
    expect(within(nav).getByRole('link', { name: 'Approvals' })).toHaveAttribute('href', '/admin/approvals')
    expect(within(nav).getByRole('link', { name: 'Users' })).toHaveAttribute('href', '/admin/users')
    expect(within(nav).getByRole('link', { name: 'Price approvals' })).toHaveAttribute('href', '/admin/price-approvals')
    expect(within(nav).getByRole('link', { name: 'Orders' })).toHaveAttribute('href', '/admin/orders')
    expect(within(nav).getByRole('link', { name: 'Payments' })).toHaveAttribute('href', '/admin/payments')
    expect(within(nav).getByRole('link', { name: 'Deliveries' })).toHaveAttribute('href', '/admin/deliveries')
    expect(within(nav).getByRole('link', { name: 'Audit log' })).toHaveAttribute('href', '/admin/audit')
    expect(within(nav).queryByText('Products')).not.toBeInTheDocument()
  })

  it('hides the Users page from admin roles that cannot list users', () => {
    const nav = renderNav('ADMIN', ['FINANCE_ADMIN'])

    expect(within(nav).getByRole('link', { name: 'Approvals' })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: 'Users' })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: 'Audit log' })).not.toBeInTheDocument()
  })

  it('shows the Phase 10 Operations page to every admin role', () => {
    for (const role of ['SUPER_ADMIN', 'OPS_ADMIN', 'FINANCE_ADMIN', 'SUPPORT', 'DELIVERY_MANAGER'] as const) {
      const nav = renderNav('ADMIN', [role])
      expect(within(nav).getByRole('link', { name: 'Operations' })).toHaveAttribute('href', '/admin/operations')
      cleanup()
    }
  })

  it('points the customer home link at /customer/home and lists account pages', () => {
    const nav = renderNav('CUSTOMER', ['CUSTOMER'])

    expect(within(nav).getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/customer/home')
    expect(within(nav).getByRole('link', { name: 'Addresses' })).toHaveAttribute('href', '/account/addresses')
    expect(within(nav).getByRole('link', { name: 'Search' })).toHaveAttribute('href', '/search')
    expect(within(nav).getByRole('link', { name: 'Restaurants' })).toHaveAttribute('href', '/restaurants')
    expect(within(nav).getByRole('link', { name: 'My orders' })).toHaveAttribute('href', '/orders')
    expect(within(nav).queryByRole('link', { name: 'Dashboard' })).not.toBeInTheDocument()
  })
})
