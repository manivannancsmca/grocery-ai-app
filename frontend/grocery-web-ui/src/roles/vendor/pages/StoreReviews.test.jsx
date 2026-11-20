import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'
import { moderatedReview } from '../../../test/storeAdminFixtures'

function setup(reviews, routes = {}) {
  const server = { reviews }
  const api = mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /reviews/summary': {
      status: 200,
      data: {
        targetType: 'VENDOR',
        targetId: 'v1',
        averageRating: 4.25,
        ratingCount: 8,
        distribution: [0, 1, 1, 1, 5],
      },
    },
    'GET /store/reviews': () => pageOf(server.reviews),
    'PUT /store/reviews/:id/reply': (request) => {
      const text = request.body.text
      const updated = {
        ...server.reviews.find((review) => review.id === request.pathParams.id),
        reply: { text, repliedAt: '2026-11-13T10:00:00Z' },
      }
      server.reviews = server.reviews.map((review) => (review.id === updated.id ? updated : review))
      return { status: 200, data: updated }
    },
    ...routes,
  })
  return { api, server }
}

describe('Store reviews (vendor and restaurant)', { timeout: 20_000 }, () => {
  it('shows the store rating and the reviews; the vendor replies publicly and can edit the reply', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const { api } = setup([
      moderatedReview(),
      moderatedReview({
        id: 'rv2',
        targetType: 'VENDOR',
        targetId: 'v1',
        targetName: 'Fresh Mart',
        rating: 2,
        title: null,
        comment: 'Late delivery.',
        reply: { text: 'Sorry about that!', repliedAt: '2026-11-12T10:00:00Z' },
      }),
    ])
    renderApp('/vendor/reviews')

    const summary = await screen.findByRole('region', { name: 'Store rating' })
    expect(summary).toHaveTextContent('4.25')
    expect(summary).toHaveTextContent('8 ratings')
    expect(api.callsTo('GET /reviews/summary')[0]?.params).toEqual({ targetType: 'VENDOR', targetId: 'v1' })
    expect(api.callsTo('GET /store/reviews')[0]?.params).toEqual({ page: 0, size: 10 })

    const list = await screen.findByRole('list', { name: 'Reviews' })
    const first = within(list).getByRole('article', { name: 'Fresh and firm' })
    expect(within(first).getByRole('img', { name: '4 out of 5 stars' })).toBeInTheDocument()
    expect(first).toHaveTextContent('Asha S.')
    expect(first).toHaveTextContent('Product: Tomato 500 g')
    const second = within(list).getByRole('article', { name: '2-star review' })
    expect(second).toHaveTextContent('Sorry about that!')

    // Reply: the text is required, then it is sent and shown under the review.
    await user.click(within(first).getByRole('button', { name: 'Reply' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reply to this review' })
    await user.click(within(dialog).getByRole('button', { name: 'Publish reply' }))
    expect(await within(dialog).findByText('Write your reply.')).toBeInTheDocument()
    expect(api.callsTo('PUT /store/reviews/:id/reply')).toHaveLength(0)
    await user.type(within(dialog).getByRole('textbox', { name: 'Your reply' }), 'Thank you, Asha!')
    await user.click(within(dialog).getByRole('button', { name: 'Publish reply' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Reply to this review' })).not.toBeInTheDocument())
    expect(api.callsTo('PUT /store/reviews/:id/reply')[0]).toMatchObject({
      pathParams: { id: 'rv1' },
      body: { text: 'Thank you, Asha!' },
    })
    expect(
      await within(screen.getByRole('article', { name: 'Fresh and firm' })).findByText('Thank you, Asha!'),
    ).toBeInTheDocument()

    // Editing starts from the existing reply.
    await user.click(
      within(screen.getByRole('article', { name: '2-star review' })).getByRole('button', { name: 'Edit reply' }),
    )
    const edit = await screen.findByRole('dialog', { name: 'Edit your reply' })
    expect(within(edit).getByRole('textbox', { name: 'Your reply' })).toHaveValue('Sorry about that!')
  })

  it('filters by what the review is about and by stars (in the URL); a review that is no longer the store’s answers 404', async () => {
    const user = userEvent.setup()
    signInAs(['RESTAURANT_OWNER'], { restaurantId: 'r1' })
    const { api } = setup([moderatedReview({ targetType: 'MENU_ITEM', targetName: 'Paneer Tikka' })], {
      'PUT /store/reviews/:id/reply': problem(404, 'REVIEW_NOT_FOUND', 'Review not found.'),
    })
    const { router } = renderApp('/restaurant/reviews')

    await screen.findByRole('list', { name: 'Reviews' })
    expect(api.callsTo('GET /reviews/summary')[0]?.params).toEqual({ targetType: 'RESTAURANT', targetId: 'r1' })
    await user.selectOptions(screen.getByRole('combobox', { name: 'About' }), 'Dishes')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Stars' }), '1 star')
    await waitFor(() =>
      expect(api.callsTo('GET /store/reviews').at(-1)?.params).toEqual({
        targetType: 'MENU_ITEM',
        rating: 1,
        page: 0,
        size: 10,
      }),
    )
    expect(new URLSearchParams(router.state.location.search).get('about')).toBe('MENU_ITEM')

    await user.click(await screen.findByRole('button', { name: 'Reply' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reply to this review' })
    await user.type(within(dialog).getByRole('textbox', { name: 'Your reply' }), 'Thanks!')
    await user.click(within(dialog).getByRole('button', { name: 'Publish reply' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('This review is no longer listed for your store')
  })
})
