import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { cartView } from '../../test/cartFixtures'
import { mockApi } from '../../test/mockApi'
import { notificationView } from '../../test/notificationFixtures'
import { pageOf } from '../../test/orderFixtures'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'

/** 25 notifications, newest first; every third one is unread. */
function inbox() {
  return Array.from({ length: 25 }, (_, index) =>
    notificationView({
      id: `n-${String(index + 1).padStart(2, '0')}`,
      title: `Update ${index + 1}`,
      read: index % 3 !== 0,
      createdAt: new Date(Date.now() - (index + 1) * 60 * 60_000).toISOString(),
    }),
  )
}

/** notification-service with paging and the `unread` filter. */
function setup(roles = ['CUSTOMER'], entries = inbox()) {
  signInAs(roles)
  const server = { entries }
  const unread = () => server.entries.filter((entry) => !entry.read)
  const api = mockApi({
    'GET /notifications/unread-count': () => ({ status: 200, data: { count: unread().length } }),
    'GET /notifications': (request) => {
      const page = Number(request.params.page ?? 0)
      const size = Number(request.params.size ?? 20)
      const matching = request.params.unread === true ? unread() : server.entries
      return pageOf(matching.slice(page * size, (page + 1) * size), {
        page,
        size,
        totalElements: matching.length,
        totalPages: Math.ceil(matching.length / size),
      })
    },
    'POST /notifications/:id/read': (request) => {
      server.entries = server.entries.map((entry) =>
        entry.id === request.pathParams.id ? { ...entry, read: true } : entry,
      )
      return { status: 204 }
    },
    'POST /notifications/read-all': () => {
      const updated = unread().length
      server.entries = server.entries.map((entry) => ({ ...entry, read: true }))
      return { status: 200, data: { updated } }
    },
    'GET /cart': { status: 200, data: cartView([]) },
  })
  return { api, server }
}

describe('Notifications page', () => {
  it('lists the inbox newest first, page by page, with unread entries marked', async () => {
    const user = userEvent.setup()
    const { api } = setup()
    const { router } = renderApp('/notifications')

    expect(await screen.findByRole('heading', { level: 1, name: 'Notifications' })).toBeInTheDocument()
    const list = await screen.findByRole('list', { name: 'All notifications' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(20)
    expect(within(list).getAllByRole('listitem')[0]).toHaveTextContent('Update 1')
    expect(within(list).getByRole('button', { name: 'Unread: Update 1' })).toHaveAccessibleDescription(
      /Order · 1 h ago/,
    )
    expect(within(list).getByRole('button', { name: 'Update 2' })).toBeInTheDocument()
    expect(api.callsTo('GET /notifications')[0]?.params).toEqual({ page: 0, size: 20 })
    await waitFor(() =>
      expect(screen.getByText(/^Live updates:/).parentElement).toHaveTextContent('Live updates: Live'),
    )

    await user.click(screen.getByRole('button', { name: 'Next' }))

    expect(
      await within(screen.getByRole('list', { name: 'All notifications' })).findByText('Update 21'),
    ).toBeInTheDocument()
    expect(new URLSearchParams(router.state.location.search).get('page')).toBe('2')
    expect(api.callsTo('GET /notifications').at(-1)?.params).toEqual({ page: 1, size: 20 })
  })

  it('filters to unread notifications', async () => {
    const user = userEvent.setup()
    const { api } = setup()
    const { router } = renderApp('/notifications')

    await screen.findByRole('list', { name: 'All notifications' })
    await user.click(screen.getByRole('tab', { name: /Unread/ }))

    const list = await screen.findByRole('list', { name: 'Unread notifications' })
    await waitFor(() => expect(within(list).getAllByRole('listitem')).toHaveLength(9))
    expect(new URLSearchParams(router.state.location.search).get('filter')).toBe('unread')
    expect(api.callsTo('GET /notifications').at(-1)?.params).toEqual({ unread: true, page: 0, size: 20 })
    expect(screen.getByRole('tab', { name: /Unread/ })).toHaveTextContent('Unread9')
  })

  it('marks one notification read without leaving the page', async () => {
    const user = userEvent.setup()
    const { api } = setup()
    const { router } = renderApp('/notifications')

    const list = await screen.findByRole('list', { name: 'All notifications' })
    await user.click(within(list).getByRole('button', { name: 'Mark as read: Update 1' }))

    await waitFor(() =>
      expect(within(list).queryByRole('button', { name: 'Unread: Update 1' })).not.toBeInTheDocument(),
    )
    expect(within(list).getByRole('button', { name: 'Update 1' })).toBeInTheDocument()
    expect(api.callsTo('POST /notifications/:id/read')[0]?.pathParams.id).toBe('n-01')
    expect(within(list).queryByRole('button', { name: 'Mark as read: Update 1' })).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/notifications')
    expect(await screen.findByRole('button', { name: 'Notifications, 8 unread' })).toBeInTheDocument()
  })

  it('opens a notification: marks it read and follows its link', async () => {
    const user = userEvent.setup()
    const { api } = setup(['CUSTOMER'], [notificationView({ link: '/orders' })])
    const { router } = renderApp('/notifications')

    const list = await screen.findByRole('list', { name: 'All notifications' })
    await user.click(within(list).getByRole('button', { name: 'Unread: Order GA-7K3F9Q2M confirmed' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/orders'))
    await waitFor(() => expect(api.callsTo('POST /notifications/:id/read')).toHaveLength(1))
  })

  it('marks everything read', async () => {
    const user = userEvent.setup()
    const { api } = setup(['FINANCE_ADMIN'])
    renderApp('/notifications')

    await screen.findByRole('list', { name: 'All notifications' })
    const markAll = screen.getAllByRole('button', { name: 'Mark all as read' })[0]
    await user.click(markAll)

    expect(await screen.findByText('Marked 9 notifications as read.')).toBeInTheDocument()
    expect(api.callsTo('POST /notifications/read-all')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: /^Unread:/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Notifications' })).toBeInTheDocument()
  })

  it('has empty states for the whole inbox and for unread', async () => {
    const user = userEvent.setup()
    setup(['RESTAURANT_OWNER'], [])
    renderApp('/notifications')

    expect(await screen.findByRole('heading', { name: 'No notifications yet' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Mark all as read' })[0]).toBeDisabled()

    await user.click(screen.getByRole('tab', { name: /Unread/ }))
    expect(await screen.findByRole('heading', { name: 'You’re all caught up' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Show all notifications' }))
    expect(await screen.findByRole('heading', { name: 'No notifications yet' })).toBeInTheDocument()
  })

  it('sends signed-out visitors to the login page', async () => {
    const { router } = renderApp('/notifications')
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(router.state.location.state).toEqual({ from: '/notifications' })
  })
})
