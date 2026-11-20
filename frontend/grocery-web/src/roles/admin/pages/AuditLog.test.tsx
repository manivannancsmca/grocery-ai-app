import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { AuditEntryView } from '../../../features/audit/api'
import { mockApi } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const ADMIN_ID = '0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e601'

function entry(overrides: Partial<AuditEntryView>): AuditEntryView {
  return {
    id: 'e1',
    occurredAt: '2026-11-20T10:00:00Z',
    category: 'ORDERS',
    action: 'ORDER_CANCELLED',
    source: 'order-service',
    actor: { id: ADMIN_ID, role: 'ADMIN', name: 'Meera Ops', email: 'meera.ops@grocery.local' },
    target: { type: 'ORDER', id: 'o1', label: 'GA-7K3F9Q2M' },
    summary: 'Order cancelled by admin',
    detail: 'admin cancelled · customer called support',
    ipAddress: null,
    userAgent: null,
    ...overrides,
  }
}

const cancelled = entry({})
const failedSignIn = entry({
  id: 'e2',
  occurredAt: '2026-11-20T09:00:00Z',
  category: 'SECURITY',
  action: 'LOGIN_FAILURE',
  source: 'identity-service',
  actor: { id: null, role: 'ANONYMOUS', name: null, email: null },
  target: { type: 'USER', id: null, label: 'intruder@example.com' },
  summary: 'Sign-in failed',
  detail: 'unknown email',
  ipAddress: '203.0.113.9',
  userAgent: 'curl/8.0',
})
const setting = entry({
  id: 'e3',
  occurredAt: '2026-11-20T08:00:00Z',
  category: 'SETTINGS',
  action: 'SETTING_CHANGED',
  source: 'delivery-service',
  target: { type: 'SETTING', id: 'delivery.offersPerWave', label: 'delivery · offersPerWave' },
  summary: 'Setting offersPerWave (delivery) changed',
  detail: '3 → 5',
})

function setup() {
  signInAs(['OPS_ADMIN'], { id: 'ops-1' })
  return mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /ai/agents': { status: 200, data: [] },
    'GET /admin/audit': (request) => {
      const p = request.params
      return pageOf(
        [cancelled, failedSignIn, setting].filter(
          (row) =>
            (!p.category || row.category === p.category) &&
            (!p.actorId || row.actor.id === p.actorId) &&
            (!p.targetId || row.target?.id === p.targetId) &&
            (!p.q || JSON.stringify(row).toLowerCase().includes(String(p.q).toLowerCase())),
        ),
      )
    },
  })
}

describe('Audit log (admin)', () => {
  it('lists what happened across the platform, newest first, with who and what it was about', async () => {
    setup()
    renderApp('/admin/audit')

    expect(await screen.findByRole('heading', { level: 1, name: 'Audit log' })).toBeInTheDocument()
    const table = await screen.findByRole('table', { name: 'Audit entries' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent('Order cancelled by admin')
    expect(rows[0]).toHaveTextContent('admin cancelled · customer called support')
    expect(rows[0]).toHaveTextContent('Meera Ops')
    expect(rows[0]).toHaveTextContent('Admin · meera.ops@grocery.local')
    expect(rows[0]).toHaveTextContent('GA-7K3F9Q2M')
    // a failed sign-in: not signed in, its address shown, and the row highlighted
    expect(rows[1]).toHaveTextContent('Sign-in failed')
    expect(rows[1]).toHaveTextContent('Not signed in')
    expect(rows[1]).toHaveTextContent('203.0.113.9')
    expect(rows[1]?.className).toMatch(/bg-amber/)
    expect(rows[2]).toHaveTextContent('3 → 5')
  })

  it('narrows the list by category, words, a person or a target, and clears it again', async () => {
    const api = setup()
    const user = userEvent.setup()
    renderApp('/admin/audit')
    const table = await screen.findByRole('table', { name: 'Audit entries' })

    await user.selectOptions(screen.getByRole('combobox', { name: 'Category' }), 'Sign-in')
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(2))
    expect(api.callsTo('GET /admin/audit').at(-1)?.params).toMatchObject({ category: 'SECURITY', page: 0, size: 50 })
    await user.click(screen.getByRole('button', { name: /Clear all/ }))
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(4))

    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'offersPerWave')
    await waitFor(() => expect(api.callsTo('GET /admin/audit').at(-1)?.params).toMatchObject({ q: 'offersPerWave' }))
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(2))
    await user.click(screen.getByRole('button', { name: /Clear all/ }))

    const first = (await within(table).findAllByRole('row'))[1] as HTMLElement
    await user.click(within(first).getByRole('button', { name: 'Only this person' }))
    await waitFor(() => expect(api.callsTo('GET /admin/audit').at(-1)?.params).toMatchObject({ actorId: ADMIN_ID }))

    const again = within(table).getAllByRole('row')[1] as HTMLElement
    await user.click(within(again).getByRole('button', { name: 'Everything about this' }))
    expect(await screen.findByText(/Only entries about/)).toHaveTextContent('Only entries about Order GA-7K3F9Q2M')
    expect(api.callsTo('GET /admin/audit').at(-1)?.params).toMatchObject({ targetType: 'ORDER', targetId: 'o1' })
    await user.click(screen.getByRole('button', { name: 'Show everything' }))
    await waitFor(() => expect(screen.queryByText(/Only entries about/)).not.toBeInTheDocument())
  })

  it('is not open to admins without the permission', async () => {
    signInAs(['FINANCE_ADMIN'])
    mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /ai/agents': { status: 200, data: [] },
    })
    renderApp('/admin/audit')

    await waitFor(() => expect(window.location.pathname).not.toBe('/admin/audit'))
    expect(screen.queryByRole('heading', { level: 1, name: 'Audit log' })).not.toBeInTheDocument()
  })
})
