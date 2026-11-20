import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { cartView } from '../../test/cartFixtures'
import { mockApi, problem } from '../../test/mockApi'
import { pageOf } from '../../test/orderFixtures'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'
import type { NotificationPreferences } from './preferencesApi'

describe('Email preferences', () => {
  it('loads the categories, saves a change, and can reset unsaved edits', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    let saved: NotificationPreferences = { email: { orders: true, account: true, approvals: true } }
    const api = mockApi({
      'GET /cart': { status: 200, data: cartView([]) },
      'GET /notifications/preferences': () => ({ status: 200, data: saved }),
      'PUT /notifications/preferences': (request) => {
        saved = request.body as NotificationPreferences
        return { status: 200, data: saved }
      },
    })
    renderApp('/notifications/preferences')

    expect(await screen.findByRole('heading', { level: 1, name: 'Email preferences' })).toBeInTheDocument()
    const orders = await screen.findByRole('switch', { name: 'Orders, refunds and returns' })
    const approvals = screen.getByRole('switch', { name: 'Assistant approvals' })
    expect(orders).toBeChecked()
    expect(screen.getByRole('switch', { name: 'Account' })).toBeChecked()
    expect(approvals).toBeChecked()
    const save = screen.getByRole('button', { name: 'Save preferences' })
    expect(save).toBeDisabled()

    // An edit can be undone before saving.
    await user.click(approvals)
    expect(approvals).not.toBeChecked()
    expect(screen.getByText('You have unsaved changes.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Reset' }))
    expect(screen.getByRole('switch', { name: 'Assistant approvals' })).toBeChecked()

    await user.click(screen.getByRole('switch', { name: 'Orders, refunds and returns' }))
    await user.click(screen.getByRole('button', { name: 'Save preferences' }))
    await waitFor(() => expect(api.callsTo('PUT /notifications/preferences')).toHaveLength(1))
    expect(api.callsTo('PUT /notifications/preferences')[0]?.body).toEqual({ email: { orders: false, account: true, approvals: true } })
    expect(await screen.findByText('Your email preferences were saved.')).toBeInTheDocument()
    expect(await screen.findByRole('switch', { name: 'Orders, refunds and returns' })).not.toBeChecked()
    expect(screen.getByRole('button', { name: 'Save preferences' })).toBeDisabled()
  })

  it('treats missing categories as on, shows a failed save, and is linked from the inbox for every role', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /notifications': pageOf([]),
      'GET /notifications/preferences': { status: 200, data: { email: { account: false } } },
      'PUT /notifications/preferences': problem(503, 'SERVICE_UNAVAILABLE', 'Notification service is down'),
    })
    const { router } = renderApp('/notifications')

    await user.click(await screen.findByRole('link', { name: 'Email preferences' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/notifications/preferences'))
    expect(await screen.findByRole('switch', { name: 'Orders, refunds and returns' })).toBeChecked()
    expect(screen.getByRole('switch', { name: 'Account' })).not.toBeChecked()

    await user.click(screen.getByRole('switch', { name: 'Account' }))
    await user.click(screen.getByRole('button', { name: 'Save preferences' }))
    const card = screen.getByRole('heading', { name: 'Email me about' }).closest('section') as HTMLElement
    expect(await within(card).findByRole('alert')).toHaveTextContent('Could not save your preferences: Notification service is down')
  })
})
