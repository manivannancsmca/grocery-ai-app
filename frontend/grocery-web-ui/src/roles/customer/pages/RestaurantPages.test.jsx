import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const spiceRoute = {
  id: 'r1',
  name: 'Spice Route',
  description: 'Dum biryani and kebabs since 1998.',
  cuisines: ['NORTH_INDIAN', 'BIRYANI'],
  openNow: true,
  acceptingOrders: true,
  pureVeg: false,
  address: {
    line1: '100 Feet Road',
    line2: 'Indiranagar',
    city: 'Bengaluru',
    state: 'Karnataka',
    postalCode: '560038',
    latitude: 12.97,
    longitude: 77.64,
  },
  openingHours: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'].map((day) => ({
    dayOfWeek: day,
    opensAt: '11:00',
    closesAt: '23:00',
  })),
  menu: [
    {
      id: 'mc1',
      name: 'Biryani',
      description: 'Slow-cooked over coal',
      items: [
        {
          id: 'i1',
          name: 'Chicken Biryani',
          description: 'Dum-cooked with basmati rice',
          price: 249,
          foodType: 'NON_VEG',
          available: true,
          imageUrl: null,
        },
        {
          id: 'i2',
          name: 'Veg Biryani',
          description: null,
          price: 199.5,
          foodType: 'VEG',
          available: false,
          imageUrl: null,
        },
      ],
    },
    {
      id: 'mc2',
      name: 'Breakfast',
      description: null,
      items: [
        {
          id: 'i3',
          name: 'Egg Bhurji',
          description: null,
          price: 99,
          foodType: 'EGG',
          available: true,
          imageUrl: null,
        },
      ],
    },
  ],
}

describe('Restaurant page', () => {
  it('shows the header and the menu, marks sold-out dishes and filters to veg only', async () => {
    const user = userEvent.setup()
    const api = mockApi({ 'GET /catalog/restaurants/:id': { status: 200, data: spiceRoute } })
    renderApp('/restaurants/r1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Spice Route' })).toBeInTheDocument()
    expect(api.callsTo('GET /catalog/restaurants/:id')[0]?.pathParams.id).toBe('r1')
    expect(screen.getByText('North Indian · Biryani')).toBeInTheDocument()
    expect(screen.getByText('Open now')).toBeInTheDocument()
    const today = screen.getByText('Today').parentElement
    expect(within(today).getByText('11:00–23:00')).toBeInTheDocument()
    expect(screen.getByText(/100 Feet Road, Indiranagar, Bengaluru, Karnataka 560038/)).toBeInTheDocument()

    const categoriesNav = screen.getByRole('navigation', { name: 'Menu categories' })
    expect(within(categoriesNav).getByRole('button', { name: /biryani/i })).toBeInTheDocument()
    expect(within(categoriesNav).getByRole('button', { name: /breakfast/i })).toBeInTheDocument()

    const biryani = screen.getByRole('region', { name: 'Biryani' })
    const chicken = within(biryani).getByRole('heading', { name: 'Chicken Biryani' }).closest('li')
    expect(within(chicken).getByText('₹249')).toBeInTheDocument()
    expect(within(chicken).getByRole('img', { name: 'Non-vegetarian' })).toBeInTheDocument()
    expect(within(chicken).queryByText('Sold out')).not.toBeInTheDocument()

    const veg = within(biryani).getByRole('heading', { name: 'Veg Biryani' }).closest('li')
    expect(within(veg).getByText('Sold out')).toBeInTheDocument()
    expect(within(veg).getByText('₹199.50')).toBeInTheDocument()

    await user.click(screen.getByRole('switch', { name: 'Veg only' }))
    expect(screen.queryByRole('heading', { name: 'Chicken Biryani' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Breakfast' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Veg Biryani' })).toBeInTheDocument()
  })

  it('warns when the restaurant is not accepting orders', async () => {
    mockApi({
      'GET /catalog/restaurants/:id': { status: 200, data: { ...spiceRoute, acceptingOrders: false, openNow: false } },
    })
    renderApp('/restaurants/r1')

    expect(await screen.findByText('Not accepting orders right now')).toBeInTheDocument()
    expect(screen.getByText('Closed')).toBeInTheDocument()
  })

  it('shows a not-found state for an unknown or hidden restaurant', async () => {
    mockApi({ 'GET /catalog/restaurants/:id': problem(404, 'RESTAURANT_NOT_FOUND', 'Restaurant not found') })
    renderApp('/restaurants/missing')

    expect(await screen.findByRole('heading', { name: 'Restaurant not found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Browse restaurants' })).toHaveAttribute('href', '/restaurants')
  })

  it('is open to signed-in vendors too (no redirect to their dashboard)', async () => {
    signInAs(['VENDOR'], { vendorId: 'v1' })
    mockApi({ 'GET /catalog/restaurants/:id': { status: 200, data: spiceRoute } })
    const { router } = renderApp('/restaurants/r1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Spice Route' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/restaurants/r1')
  })
})

describe('Restaurants page', () => {
  const page = {
    content: [
      {
        id: 'r1',
        name: 'Spice Route',
        description: null,
        cuisines: ['BIRYANI'],
        city: 'Bengaluru',
        locality: 'Indiranagar',
        distanceKm: null,
        openNow: true,
        acceptingOrders: true,
        pureVeg: false,
        matchingItems: [{ id: 'i1', name: 'Chicken Biryani', price: 249, foodType: 'NON_VEG' }],
      },
    ],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
    facets: {
      cuisines: [
        { value: 'BIRYANI', count: 1 },
        { value: 'SOUTH_INDIAN', count: 3 },
      ],
    },
  }

  it('keeps cuisines and toggles in the URL and sends them to the search', async () => {
    const user = userEvent.setup()
    const api = mockApi({ 'GET /search/restaurants': { status: 200, data: page } })
    const { router } = renderApp('/restaurants?q=biryani')

    const card = (await screen.findByRole('link', { name: 'Spice Route' })).closest('article')
    expect(within(card).getByText('Chicken Biryani')).toBeInTheDocument()
    expect(within(card).getByText('₹249')).toBeInTheDocument()
    expect(screen.getByText('Everywhere — distances are not shown')).toBeInTheDocument()
    expect(api.callsTo('GET /search/restaurants')[0]?.params).toEqual({ q: 'biryani', page: 0, size: 20 })

    const cuisines = screen.getByRole('list', { name: 'Cuisines' })
    await user.click(within(cuisines).getByRole('button', { name: /biryani/i }))
    await user.click(within(cuisines).getByRole('button', { name: /south indian/i }))
    await user.click(screen.getByRole('switch', { name: 'Pure veg' }))

    const params = new URLSearchParams(router.state.location.search)
    expect(params.getAll('cuisine')).toEqual(['BIRYANI', 'SOUTH_INDIAN'])
    expect(params.get('pureVeg')).toBe('true')
    expect(within(cuisines).getByRole('button', { name: /south indian/i })).toHaveAttribute('aria-pressed', 'true')

    await waitFor(() =>
      expect(api.callsTo('GET /search/restaurants').at(-1)?.params).toEqual({
        q: 'biryani',
        cuisine: ['BIRYANI', 'SOUTH_INDIAN'],
        pureVeg: true,
        page: 0,
        size: 20,
      }),
    )
  })
})
