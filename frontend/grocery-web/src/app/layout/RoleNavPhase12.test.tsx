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

function links(nav: HTMLElement): Record<string, string | null> {
  return Object.fromEntries(within(nav).getAllByRole('link').map((link) => [link.textContent ?? '', link.getAttribute('href')]))
}

describe('RoleNav — Phase 12 screens', () => {
  it('vendor: reviews, returns, coupons, suppliers, purchase orders and the assistant’s actions', () => {
    expect(links(renderNav('VENDOR', ['VENDOR']))).toMatchObject({
      Reviews: '/vendor/reviews',
      Returns: '/vendor/returns',
      Coupons: '/vendor/coupons',
      Suppliers: '/vendor/suppliers',
      'Purchase orders': '/vendor/purchase-orders',
      'Assistant actions': '/vendor/assistant',
    })
  })

  it('restaurant: reviews, returns, coupons and the assistant’s actions (no purchasing)', () => {
    const nav = links(renderNav('RESTAURANT_OWNER', ['RESTAURANT_OWNER']))
    expect(nav).toMatchObject({
      Reviews: '/restaurant/reviews',
      Returns: '/restaurant/returns',
      Coupons: '/restaurant/coupons',
      'Assistant actions': '/restaurant/assistant',
    })
    expect(nav).not.toHaveProperty('Purchase orders')
  })

  it('delivery partner: the assistant’s actions', () => {
    expect(links(renderNav('DELIVERY_PARTNER', ['DELIVERY_PARTNER']))).toMatchObject({ 'Assistant actions': '/delivery/assistant' })
  })

  it('customer: returns, then Help & support (complaints) right after them', () => {
    const nav = links(renderNav('CUSTOMER', ['CUSTOMER']))
    expect(nav).toMatchObject({ 'My returns': '/account/returns', 'Help & support': '/account/complaints' })
    const labels = Object.keys(nav)
    expect(labels.indexOf('Help & support')).toBe(labels.indexOf('My returns') + 1)
  })

  it('every admin role: reports, reviews, returns, complaints, coupons, purchase orders and the email log', () => {
    for (const role of ['SUPER_ADMIN', 'OPS_ADMIN', 'FINANCE_ADMIN', 'SUPPORT', 'DELIVERY_MANAGER'] as const) {
      expect(links(renderNav('ADMIN', [role]))).toMatchObject({
        Reports: '/admin/reports',
        Reviews: '/admin/reviews',
        Returns: '/admin/returns',
        Complaints: '/admin/complaints',
        Coupons: '/admin/coupons',
        'Purchase orders': '/admin/purchase-orders',
        'Email log': '/admin/emails',
      })
      cleanup()
    }
  })
})
