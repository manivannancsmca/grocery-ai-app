import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { ModeratedReviewView } from '../../../features/store-reviews/api'
import type { UserRole } from '../../../shared/auth/roles'
import { type MockHandler, mockApi, problem } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'
import { moderatedReview } from '../../../test/storeAdminFixtures'

const flagged = moderatedReview({
  id: 'rv9',
  status: 'FLAGGED',
  flagReason: 'LINK',
  title: 'Buy cheaper at my site',
  comment: 'Visit http://spam.example now',
})

function setup(roles: UserRole[], reviews: ModeratedReviewView[], routes: Record<string, MockHandler> = {}) {
  signInAs(roles, { id: 'admin-1' })
  const server = { reviews }
  const decide = (status: string) => (request: { pathParams: Record<string, string> }) => {
    const review = server.reviews.find((candidate) => candidate.id === request.pathParams.id) as ModeratedReviewView
    server.reviews = server.reviews.filter((candidate) => candidate.id !== review.id)
    return { status: 200, data: { ...review, status } }
  }
  const api = mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /admin/reviews': (request) => pageOf(server.reviews.filter((review) => review.status === request.params.status)),
    'POST /admin/reviews/:id/publish': decide('PUBLISHED'),
    'POST /admin/reviews/:id/reject': decide('REJECTED'),
    'POST /admin/reviews/:id/remove': decide('REMOVED'),
    ...routes,
  })
  return { api, server }
}

describe('Admin review moderation', { timeout: 20_000 }, () => {
  it('lists flagged reviews by default with the reason; publish and reject (a reason is required)', async () => {
    const user = userEvent.setup()
    const other = moderatedReview({ id: 'rv8', status: 'FLAGGED', flagReason: 'PROFANITY', title: 'Rude words' })
    const { api } = setup(['SUPPORT'], [flagged, other])
    renderApp('/admin/reviews')

    const list = await screen.findByRole('list', { name: 'Reviews' })
    expect(api.callsTo('GET /admin/reviews')[0]?.params).toEqual({ status: 'FLAGGED', page: 0, size: 10 })
    const card = within(list).getByRole('article', { name: 'Buy cheaper at my site' })
    expect(card).toHaveTextContent('Flagged automatically: Contains a link')
    expect(card).toHaveTextContent('Fresh Mart')

    await user.click(within(card).getByRole('button', { name: 'Publish' }))
    await waitFor(() => expect(api.callsTo('POST /admin/reviews/:id/publish')).toHaveLength(1))
    expect(api.callsTo('POST /admin/reviews/:id/publish')[0]?.pathParams.id).toBe('rv9')
    await waitFor(() => expect(screen.queryByRole('article', { name: 'Buy cheaper at my site' })).not.toBeInTheDocument())

    await user.click(within(screen.getByRole('article', { name: 'Rude words' })).getByRole('button', { name: 'Reject' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reject this flagged review?' })
    await user.click(within(dialog).getByRole('button', { name: 'Reject review' }))
    expect(await within(dialog).findByText(/Enter a reason/)).toBeInTheDocument()
    expect(api.callsTo('POST /admin/reviews/:id/reject')).toHaveLength(0)
    await user.type(within(dialog).getByRole('textbox', { name: 'Reason' }), 'Offensive language')
    await user.click(within(dialog).getByRole('button', { name: 'Reject review' }))
    await waitFor(() => expect(api.callsTo('POST /admin/reviews/:id/reject')[0]?.body).toEqual({ reason: 'Offensive language' }))
    expect(await screen.findByText('Nothing to moderate')).toBeInTheDocument()
  })

  it('removes a published review; a 409 REVIEW_NOT_MODERATABLE explains and refreshes', async () => {
    const user = userEvent.setup()
    const published = moderatedReview({ id: 'rv5', title: 'Great store' })
    const { api } = setup(['OPS_ADMIN'], [published], {
      'POST /admin/reviews/:id/remove': { ...problem(409, 'REVIEW_NOT_MODERATABLE', 'Review is not moderatable.'), data: { ...problem(409, 'REVIEW_NOT_MODERATABLE').data, currentStatus: 'REMOVED' } },
    })
    const { router } = renderApp('/admin/reviews?status=PUBLISHED')

    const card = await screen.findByRole('article', { name: 'Great store' })
    expect(within(card).queryByRole('button', { name: 'Publish' })).not.toBeInTheDocument()
    const reads = api.callsTo('GET /admin/reviews').length
    await user.click(within(card).getByRole('button', { name: 'Remove' }))
    const dialog = await screen.findByRole('dialog', { name: 'Remove this published review?' })
    await user.type(within(dialog).getByRole('textbox', { name: 'Reason' }), 'Spam')
    await user.click(within(dialog).getByRole('button', { name: 'Remove review' }))

    expect(await within(card).findByRole('alert')).toHaveTextContent('This review was already moderated, so nothing was changed. It is removed now.')
    expect(screen.queryByRole('dialog', { name: 'Remove this published review?' })).not.toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /admin/reviews').length).toBeGreaterThan(reads))

    // Filters live in the URL.
    await user.selectOptions(screen.getByRole('combobox', { name: 'About' }), 'Restaurant')
    await waitFor(() => expect(api.callsTo('GET /admin/reviews').at(-1)?.params).toEqual({ status: 'PUBLISHED', targetType: 'RESTAURANT', page: 0, size: 10 }))
    expect(new URLSearchParams(router.state.location.search).get('targetType')).toBe('RESTAURANT')
  })

  it('a finance admin reads but cannot moderate', async () => {
    setup(['FINANCE_ADMIN'], [flagged])
    renderApp('/admin/reviews')
    const card = await screen.findByRole('article', { name: 'Buy cheaper at my site' })
    expect(within(card).queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByText(/Your role can read reviews/)).toBeInTheDocument()
  })
})
