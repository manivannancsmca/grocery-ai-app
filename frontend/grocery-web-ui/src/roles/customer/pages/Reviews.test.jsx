import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { cartView } from '../../../test/cartFixtures'
import { mockApi, problem } from '../../../test/mockApi'
import { confirmedOrder, pageOf } from '../../../test/orderFixtures'
import { daysAgo, eligibleTarget, reviewView } from '../../../test/phase12Fixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

/** Order GA-7K3F9Q2M: the rice, the store and the delivery partner can be reviewed. */
const TARGETS = [
  eligibleTarget(),
  eligibleTarget({ targetType: 'VENDOR', targetId: 'v1', targetName: 'Green Basket' }),
  eligibleTarget({ targetType: 'DELIVERY_PARTNER', targetId: 'dp1', targetName: 'Ravi' }),
]

/** review-service for one customer: eligibility, create, edit and delete keep `server.reviews` in step. */
function reviewServer(initial = [], routes = {}) {
  signInAs(['CUSTOMER'])
  const server = { reviews: [...initial] }
  let next = 10
  const reviewOf = (target) =>
    server.reviews.find((review) => review.targetType === target.targetType && review.targetId === target.targetId) ??
    null
  const api = mockApi({
    'GET /cart': { status: 200, data: cartView([]) },
    'GET /reviews/eligible': () => ({
      status: 200,
      data: TARGETS.map((target) => ({ ...target, review: reviewOf(target) })),
    }),
    'POST /reviews': (request) => {
      const body = request.body
      const created = reviewView({
        id: `rv${next++}`,
        targetType: body.targetType,
        targetId: body.targetId,
        targetName: TARGETS.find((target) => target.targetId === body.targetId)?.targetName ?? null,
        rating: body.rating,
        title: body.title ?? null,
        comment: body.comment ?? null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      })
      server.reviews.push(created)
      return { status: 201, data: created }
    },
    'PUT /reviews/:id': (request) => {
      const body = request.body
      const existing = server.reviews.find((review) => review.id === request.pathParams.id)
      if (!existing) return problem(404, 'REVIEW_NOT_FOUND')
      const updated = {
        ...existing,
        rating: body.rating,
        title: body.title ?? null,
        comment: body.comment ?? null,
        edited: true,
        updatedAt: new Date().toISOString(),
      }
      server.reviews = server.reviews.map((review) => (review.id === updated.id ? updated : review))
      return { status: 200, data: updated }
    },
    'DELETE /reviews/:id': (request) => {
      server.reviews = server.reviews.filter((review) => review.id !== request.pathParams.id)
      return { status: 204 }
    },
    ...routes,
  })
  return { api, server }
}

describe('Rate your order', () => {
  it('lists the items, the store and the partner; submits stars and text, then edits and deletes the review', async () => {
    const user = userEvent.setup()
    const { api } = reviewServer()
    renderApp('/orders/o1/review')

    expect(await screen.findByRole('heading', { level: 1, name: 'Rate your order' })).toBeInTheDocument()
    expect(await screen.findByText('0 of 3 rated')).toBeInTheDocument()
    expect(screen.getByText(/Order GA-7K3F9Q2M · delivered/)).toBeInTheDocument()
    expect(
      within(screen.getByRole('region', { name: 'What you ordered' })).getByRole('heading', { name: 'Basmati Rice' }),
    ).toBeInTheDocument()
    expect(
      within(screen.getByRole('region', { name: 'The store' })).getByRole('heading', { name: 'Green Basket' }),
    ).toBeInTheDocument()
    expect(
      within(screen.getByRole('region', { name: 'The delivery' })).getByRole('heading', { name: 'Ravi' }),
    ).toBeInTheDocument()

    const rice = screen.getByRole('region', { name: 'Basmati Rice' })
    // Stars are required.
    await user.click(within(rice).getByRole('button', { name: 'Submit review' }))
    expect(within(rice).getByText('Choose a rating from 1 to 5 stars.')).toBeInTheDocument()
    expect(api.callsTo('POST /reviews')).toHaveLength(0)

    const stars = within(rice).getByRole('group', { name: 'Your rating for Basmati Rice' })
    await user.click(within(stars).getByRole('radio', { name: '4 stars — Good' }))
    await user.type(within(rice).getByRole('textbox', { name: /Title/ }), '  Fragrant rice ')
    await user.type(within(rice).getByRole('textbox', { name: /Your review/ }), 'Cooks well.')
    await user.click(within(rice).getByRole('button', { name: 'Submit review' }))

    await waitFor(() => expect(api.callsTo('POST /reviews')).toHaveLength(1))
    expect(api.callsTo('POST /reviews')[0]?.body).toEqual({
      orderId: 'o1',
      targetType: 'PRODUCT',
      targetId: 'p5',
      rating: 4,
      title: 'Fragrant rice',
      comment: 'Cooks well.',
    })
    expect(await screen.findByText('Thanks for your review!')).toBeInTheDocument()
    expect(await screen.findByText('1 of 3 rated')).toBeInTheDocument()
    let riceNow = screen.getByRole('region', { name: 'Basmati Rice' })
    expect(within(riceNow).getByRole('img', { name: '4 out of 5 stars' })).toBeInTheDocument()
    expect(within(riceNow).getByText('Published')).toBeInTheDocument()
    expect(within(riceNow).getByText('Cooks well.')).toBeInTheDocument()

    // Edit within 7 days: the form starts from the review.
    await user.click(within(riceNow).getByRole('button', { name: 'Edit your review of Basmati Rice' }))
    riceNow = screen.getByRole('region', { name: 'Basmati Rice' })
    expect(within(riceNow).getByRole('radio', { name: '4 stars — Good' })).toBeChecked()
    expect(within(riceNow).getByRole('textbox', { name: /Title/ })).toHaveValue('Fragrant rice')
    await user.click(within(riceNow).getByRole('radio', { name: '5 stars — Excellent' }))
    await user.click(within(riceNow).getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(api.callsTo('PUT /reviews/:id')).toHaveLength(1))
    expect(api.callsTo('PUT /reviews/:id')[0]?.path).toBe('/reviews/rv10')
    expect(api.callsTo('PUT /reviews/:id')[0]?.body).toEqual({
      rating: 5,
      title: 'Fragrant rice',
      comment: 'Cooks well.',
    })
    expect(
      await within(screen.getByRole('region', { name: 'Basmati Rice' })).findByRole('img', {
        name: '5 out of 5 stars',
      }),
    ).toBeInTheDocument()

    // Delete (any time), after confirming.
    await user.click(
      within(screen.getByRole('region', { name: 'Basmati Rice' })).getByRole('button', {
        name: 'Delete your review of Basmati Rice',
      }),
    )
    const dialog = await screen.findByRole('dialog', { name: 'Delete your review?' })
    await user.click(within(dialog).getByRole('button', { name: 'Delete review' }))
    await waitFor(() => expect(api.callsTo('DELETE /reviews/:id')).toHaveLength(1))
    expect(await screen.findByText('Your review was deleted.')).toBeInTheDocument()
    expect(await screen.findByText('0 of 3 rated')).toBeInTheDocument()
    expect(
      within(screen.getByRole('region', { name: 'Basmati Rice' })).getByRole('button', { name: 'Submit review' }),
    ).toBeInTheDocument()
  })

  it('shows a flagged review as under review, the store’s reply, and no "Edit" after 7 days; refusals are explained', async () => {
    const user = userEvent.setup()
    const old = reviewView({
      targetType: 'VENDOR',
      targetId: 'v1',
      targetName: 'Green Basket',
      status: 'FLAGGED',
      createdAt: daysAgo(8),
      reply: { text: 'Sorry about the delay, Asha!', repliedAt: daysAgo(2) },
    })
    const { api } = reviewServer([old], { 'POST /reviews': problem(409, 'ALREADY_REVIEWED') })
    renderApp('/orders/o1/review')

    const store = await screen.findByRole('region', { name: 'Green Basket' })
    expect(within(store).getByText('Under review')).toBeInTheDocument()
    expect(
      within(store).getByText('Our team is checking this review. It stays hidden until it is approved.'),
    ).toBeInTheDocument()
    expect(within(store).getByText('Sorry about the delay, Asha!')).toBeInTheDocument()
    expect(within(store).getByText(/Reply from the store/)).toBeInTheDocument()
    expect(within(store).queryByRole('button', { name: /Edit your review/ })).not.toBeInTheDocument()
    expect(within(store).getByText('The 7-day editing period is over.')).toBeInTheDocument()
    expect(within(store).getByRole('button', { name: 'Delete your review of Green Basket' })).toBeInTheDocument()

    const partner = screen.getByRole('region', { name: 'Ravi' })
    await user.click(within(partner).getByRole('radio', { name: '2 stars — Poor' }))
    await user.click(within(partner).getByRole('button', { name: 'Submit review' }))
    expect(await within(partner).findByRole('alert')).toHaveTextContent(
      'You have already reviewed this. You can edit your review instead.',
    )
    expect(api.callsTo('POST /reviews')[0]?.body).toEqual({
      orderId: 'o1',
      targetType: 'DELIVERY_PARTNER',
      targetId: 'dp1',
      rating: 2,
    })
  })

  it('says when nothing can be rated (window over)', async () => {
    signInAs(['CUSTOMER'])
    mockApi({ 'GET /cart': { status: 200, data: cartView([]) }, 'GET /reviews/eligible': { status: 200, data: [] } })
    renderApp('/orders/o1/review')

    expect(await screen.findByText('Nothing to rate for this order')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to the order' })).toHaveAttribute('href', '/orders/o1')
  })

  it('offers "Rate your order" on a delivered order', async () => {
    signInAs(['CUSTOMER'])
    const delivered = confirmedOrder({
      status: 'DELIVERED',
      delivery: { partnerName: 'Ravi', assignedAt: daysAgo(1.1), pickedUpAt: daysAgo(1.05), deliveredAt: daysAgo(1) },
    })
    mockApi({ 'GET /cart': { status: 200, data: cartView([]) }, 'GET /orders/:id': { status: 200, data: delivered } })
    renderApp('/orders/o1')

    const after = await screen.findByRole('region', { name: 'After delivery' })
    expect(within(after).getByRole('link', { name: 'Rate your order' })).toHaveAttribute('href', '/orders/o1/review')
    // Not returnable (the server did not say so): no return button.
    expect(within(after).queryByRole('button', { name: 'Return items' })).not.toBeInTheDocument()
  })
})

describe('My reviews', () => {
  it('lists every review with its status and reply, links orders waiting for a review, and deletes', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    const published = reviewView({ reply: { text: 'Thank you!', repliedAt: daysAgo(0.5) } })
    const rejected = reviewView({
      id: 'rv2',
      targetType: 'RESTAURANT',
      targetId: 'r1',
      targetName: 'Spice Route',
      orderId: 'o2',
      orderNumber: 'GA-REST0001',
      rating: 1,
      title: 'CALL ME NOW',
      status: 'REJECTED',
      moderationReason: 'Contact details',
      createdAt: daysAgo(20),
    })
    let mine = [published, rejected]
    const api = mockApi({
      'GET /cart': { status: 200, data: cartView([]) },
      'GET /reviews/mine': () => pageOf(mine, { size: 10 }),
      'GET /reviews/eligible': {
        status: 200,
        data: [
          eligibleTarget({ orderId: 'o3', orderNumber: 'GA-NEW00003', targetId: 'p9', targetName: 'Atta' }),
          eligibleTarget({
            orderId: 'o3',
            orderNumber: 'GA-NEW00003',
            targetType: 'VENDOR',
            targetId: 'v1',
            targetName: 'Green Basket',
          }),
          eligibleTarget({ review: published }),
        ],
      },
      'DELETE /reviews/:id': (request) => {
        mine = mine.filter((review) => review.id !== request.pathParams.id)
        return { status: 204 }
      },
    })
    renderApp('/account/reviews')

    expect(await screen.findByRole('heading', { level: 1, name: 'My reviews' })).toBeInTheDocument()
    expect(api.callsTo('GET /reviews/mine')[0]?.params).toEqual({ page: 0, size: 10 })
    const waiting = await screen.findByRole('region', { name: 'Waiting for your review' })
    expect(within(waiting).getByText(/2 to rate/)).toBeInTheDocument()
    expect(within(waiting).getByRole('link', { name: 'Rate order GA-NEW00003' })).toHaveAttribute(
      'href',
      '/orders/o3/review',
    )

    const list = await screen.findByRole('list', { name: 'Your reviews' })
    const rice = within(list).getByRole('region', { name: 'Basmati Rice' })
    expect(within(rice).getByText('Published')).toBeInTheDocument()
    expect(within(rice).getByText('Thank you!')).toBeInTheDocument()
    expect(within(rice).getByRole('link', { name: 'GA-7K3F9Q2M' })).toHaveAttribute('href', '/orders/o1')
    const spice = within(list).getByRole('region', { name: 'Spice Route' })
    expect(within(spice).getByText('Not published')).toBeInTheDocument()
    expect(within(spice).getByText('Reason: Contact details')).toBeInTheDocument()
    expect(within(spice).queryByRole('button', { name: /Edit/ })).not.toBeInTheDocument()

    await user.click(within(spice).getByRole('button', { name: 'Delete your review of Spice Route' }))
    await user.click(
      within(await screen.findByRole('dialog', { name: 'Delete your review?' })).getByRole('button', {
        name: 'Delete review',
      }),
    )
    await waitFor(() => expect(api.callsTo('DELETE /reviews/:id')[0]?.path).toBe('/reviews/rv2'))
    await waitFor(() =>
      expect(
        within(screen.getByRole('list', { name: 'Your reviews' })).queryByRole('region', { name: 'Spice Route' }),
      ).not.toBeInTheDocument(),
    )
  })
})
