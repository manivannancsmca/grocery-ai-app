import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createElement } from 'react'
import { describe, expect, it } from 'vitest'
import { NOTIFICATION_TYPES, parseNotification, parseSignal, SIGNAL_TYPES } from '../../shared/realtime/messages'
import { aiStatus, completedOrder, placeOrderApproval, SHOPPING_AGENT } from '../../test/aiFixtures'
import { cartView } from '../../test/cartFixtures'
import { mockApi } from '../../test/mockApi'
import { mockChatStream } from '../../test/mockChatStream'
import { mockEventStream } from '../../test/mockEventStream'
import { notificationView } from '../../test/notificationFixtures'
import { pageOf } from '../../test/orderFixtures'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'
import { cartKeys } from '../cart/api'
import { NotificationIcon } from '../notifications/NotificationIcon'
import { notificationLabel } from '../notifications/style'
import { notificationTarget } from '../notifications/useOpenNotification'
import { orderKeys } from '../orders/api'
import { invalidationForNotification, invalidationForSignal } from '../realtime/invalidation'
import { approvalKeys } from './api'

/** Contract §8: the four notification types, as notification-service sends them. */
const requested = notificationView({
  id: 'n-ai-1',
  type: 'AI_APPROVAL_REQUESTED',
  category: 'APPROVAL',
  severity: 'INFO',
  title: 'Approval needed: Place order: 2 items from Fresh Mart',
  body: 'Proposed by the Shopping assistant. Expires at 10:20.',
  link: '/assistant/approvals?id=a1',
  data: { approvalId: 'a1', action: 'PLACE_ORDER' },
})
const decided = notificationView({
  id: 'n-ai-2',
  type: 'AI_APPROVAL_DECIDED',
  category: 'APPROVAL',
  severity: 'WARNING',
  title: 'Not approved: Refund ₹2,500.00 for GA-7K3F9Q2M',
  body: 'Not enough evidence',
  link: '/admin/ai/approvals?id=a2',
  data: { approvalId: 'a2', action: 'REFUND_PAYMENT' },
})
const completed = notificationView({
  id: 'n-ai-3',
  type: 'AI_ACTION_COMPLETED',
  category: 'APPROVAL',
  severity: 'SUCCESS',
  title: 'Done: Place order: 2 items from Fresh Mart',
  body: 'GA-AI000001',
  link: '/assistant/approvals?id=a1',
  data: { approvalId: 'a1', action: 'PLACE_ORDER' },
})
const failed = notificationView({
  id: 'n-ai-4',
  type: 'AI_ACTION_FAILED',
  category: 'APPROVAL',
  severity: 'ERROR',
  title: 'Could not complete: Place order: 2 items from Fresh Mart',
  body: 'The total changed since the preview.',
  link: '/assistant/approvals?id=a1',
  data: { approvalId: 'a1', action: 'PLACE_ORDER' },
})

function iconHtml(type, severity) {
  const { container, unmount } = render(createElement(NotificationIcon, { type, category: 'APPROVAL', severity }))
  const html = container.innerHTML
  unmount()
  return html
}

describe('Phase 11 notifications and signal', () => {
  it('knows the four types: labels, own icons coloured by severity, links and invalidations', () => {
    for (const type of ['AI_APPROVAL_REQUESTED', 'AI_APPROVAL_DECIDED', 'AI_ACTION_COMPLETED', 'AI_ACTION_FAILED']) {
      expect(NOTIFICATION_TYPES).toContain(type)
    }
    expect(notificationLabel(requested)).toBe('Approval needed')
    expect(notificationLabel(decided)).toBe('Not approved')
    expect(notificationLabel(completed)).toBe('Assistant action done')
    expect(notificationLabel(failed)).toBe('Assistant action failed')

    const category = iconHtml(undefined, 'INFO')
    const icons = [
      iconHtml('AI_APPROVAL_REQUESTED', 'INFO'),
      iconHtml('AI_APPROVAL_DECIDED', 'WARNING'),
      iconHtml('AI_ACTION_COMPLETED', 'SUCCESS'),
      iconHtml('AI_ACTION_FAILED', 'ERROR'),
    ]
    expect(new Set(icons).size).toBe(4)
    expect(icons.every((html) => html !== category)).toBe(true)
    expect(icons[0]).toContain('sky')
    expect(icons[1]).toContain('amber')
    expect(icons[2]).toContain('emerald')
    expect(icons[3]).toContain('red')

    // Without a severity in the payload, the contract's one is used.
    expect(parseNotification(JSON.stringify({ ...failed, severity: undefined }))?.severity).toBe('ERROR')
    expect(parseNotification(JSON.stringify({ ...decided, severity: undefined }))?.severity).toBe('WARNING')

    expect(notificationTarget(requested)).toBe('/assistant/approvals?id=a1')
    expect(notificationTarget(decided)).toBe('/admin/ai/approvals?id=a2')

    expect(invalidationForNotification(requested).keys).toEqual([approvalKeys.lists(), approvalKeys.detail('a1')])
    expect(invalidationForNotification(decided).keys).toEqual([approvalKeys.lists(), approvalKeys.detail('a2')])
    expect(invalidationForNotification(failed).keys).toEqual([approvalKeys.lists(), approvalKeys.detail('a1')])
    // A placed order also refreshes the cart and the orders.
    expect(invalidationForNotification(completed).keys).toEqual([
      approvalKeys.lists(),
      approvalKeys.detail('a1'),
      cartKeys.all,
      orderKeys.all,
    ])
  })

  it('AI_APPROVALS_CHANGED → the approval lists and that request (every request without an id)', () => {
    expect(SIGNAL_TYPES).toContain('AI_APPROVALS_CHANGED')
    const signal = parseSignal(
      JSON.stringify({ type: 'AI_APPROVALS_CHANGED', data: { approvalId: 'a1' }, at: '2026-11-13T10:00:00Z' }),
    )
    expect(signal).toEqual({ type: 'AI_APPROVALS_CHANGED', data: { approvalId: 'a1' }, at: '2026-11-13T10:00:00Z' })
    expect(invalidationForSignal(signal).keys).toEqual([approvalKeys.lists(), approvalKeys.detail('a1')])
    expect(invalidationForSignal({ type: 'AI_APPROVALS_CHANGED', data: {}, at: '2026-11-13T10:00:00Z' }).keys).toEqual([
      approvalKeys.lists(),
      approvalKeys.details(),
    ])
  })

  it('a live AI_APPROVALS_CHANGED refreshes an open approval card in the chat', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    let reads = 0
    mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /ai/agents': { status: 200, data: [SHOPPING_AGENT] },
      'GET /ai/status': { status: 200, data: aiStatus() },
      'GET /cart': { status: 200, data: cartView([]) },
      'GET /orders': pageOf([]),
      // Approved in another tab meanwhile.
      'GET /approvals/:id': () => {
        reads++
        return { status: 200, data: completedOrder() }
      },
    })
    const sse = mockEventStream()
    const chat = mockChatStream()
    renderApp('/orders')

    await user.click(await screen.findByRole('button', { name: 'Open the assistant' }))
    const panel = await screen.findByRole('dialog', { name: 'Shopping assistant' })
    await user.type(within(panel).getByRole('textbox', { name: 'Message the assistant' }), 'place my order')
    await user.click(within(panel).getByRole('button', { name: 'Send' }))
    const stream = await chat.connection()
    stream.approval(placeOrderApproval())
    stream.done()
    const card = await within(panel).findByRole('region', { name: 'Place order: 2 items from Fresh Mart' })
    expect(within(card).getByRole('button', { name: 'Confirm order' })).toBeInTheDocument()
    expect(reads).toBe(0)

    const live = await sse.connection()
    live.signal({ type: 'AI_APPROVALS_CHANGED', data: { approvalId: 'a1' } })

    expect(await within(card).findByText('Order GA-AI000001 placed')).toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: 'Confirm order' })).not.toBeInTheDocument()
    await waitFor(() => expect(reads).toBe(1))
  })

  it('opening AI_APPROVAL_REQUESTED from the bell opens the customer’s request', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 1 } },
      'GET /notifications': pageOf([requested], { size: 10 }),
      'POST /notifications/:id/read': { status: 204 },
      'GET /ai/agents': { status: 200, data: [SHOPPING_AGENT] },
      'GET /cart': { status: 200, data: cartView([]) },
      'GET /orders': pageOf([]),
      'GET /approvals': pageOf([placeOrderApproval()]),
      'GET /approvals/:id': { status: 200, data: placeOrderApproval() },
    })
    const { router } = renderApp('/orders')

    await user.click(await screen.findByRole('button', { name: 'Notifications, 1 unread' }))
    const panel = screen.getByRole('region', { name: 'Notifications' })
    const item = await within(panel).findByRole('button', {
      name: 'Unread: Approval needed: Place order: 2 items from Fresh Mart',
    })
    expect(item).toHaveAccessibleDescription(/Approval needed · 2 min ago$/)
    await user.click(item)

    await waitFor(() => expect(router.state.location.pathname).toBe('/assistant/approvals'))
    const drawer = await screen.findByRole('dialog', { name: 'Place order: 2 items from Fresh Mart' })
    expect(within(drawer).getByRole('button', { name: 'Confirm order' })).toBeInTheDocument()
  })
})
