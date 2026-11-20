import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

function chickenBiryani(overrides = {}) {
  return {
    id: 'i1',
    categoryId: 'mc1',
    name: 'Chicken Biryani',
    description: 'Dum-cooked with basmati rice',
    price: 249,
    foodType: 'NON_VEG',
    imageUrl: null,
    available: true,
    sortOrder: 0,
    updatedAt: '2026-11-11T10:00:00Z',
    ...overrides,
  }
}

function menu(overrides = {}) {
  return {
    restaurantId: 'r1',
    restaurantStatus: 'APPROVED',
    acceptingOrders: true,
    itemCount: 1,
    categories: [{ id: 'mc1', name: 'Biryani', description: null, sortOrder: 0, items: [chickenBiryani()] }],
    ...overrides,
  }
}

/** In-memory restaurant-service: the menu changes as the editor calls the API. */
function menuApi(initial) {
  let state = initial
  const items = () => state.categories.flatMap((category) => category.items)
  const replaceItem = (item) => {
    state = {
      ...state,
      categories: state.categories.map((category) => ({
        ...category,
        items: category.items.map((existing) => (existing.id === item.id ? item : existing)),
      })),
    }
  }
  return mockApi({
    'GET /restaurant/menu': () => ({ status: 200, data: state }),
    'POST /restaurant/menu/items': (request) => {
      const body = request.body
      const item = { ...body, id: `i${items().length + 1}`, updatedAt: '2026-11-11T11:00:00Z' }
      state = {
        ...state,
        itemCount: state.itemCount + 1,
        categories: state.categories.map((category) =>
          category.id === body.categoryId ? { ...category, items: [...category.items, item] } : category,
        ),
      }
      return { status: 201, data: item }
    },
    'PATCH /restaurant/menu/items/:id/availability': (request) => {
      const current = items().find((item) => item.id === request.pathParams.id)
      if (!current) return problem(404, 'MENU_ITEM_NOT_FOUND')
      const updated = { ...current, available: request.body.available }
      replaceItem(updated)
      return { status: 200, data: updated }
    },
    'DELETE /restaurant/menu/categories/:id': problem(409, 'MENU_CATEGORY_NOT_EMPTY', 'Category still has items'),
    'PUT /restaurant/accepting-orders': (request) => {
      const { acceptingOrders } = request.body
      state = { ...state, acceptingOrders }
      return { status: 200, data: { acceptingOrders } }
    },
  })
}

describe('Restaurant menu editor', () => {
  it('adds a dish with validation and toggles a dish’s availability', async () => {
    const user = userEvent.setup()
    signInAs(['RESTAURANT_OWNER'], { restaurantId: 'r1' })
    const api = menuApi(menu())
    renderApp('/restaurant/menu')

    const biryani = await screen.findByRole('region', { name: 'Biryani' })
    expect(within(biryani).getByText('₹249')).toBeInTheDocument()

    await user.click(within(biryani).getByRole('button', { name: 'Add dish to Biryani' }))
    const dialog = await screen.findByRole('dialog', { name: 'Add dish' })
    expect(within(dialog).getByLabelText(/^Category/)).toHaveValue('mc1')

    await user.click(within(dialog).getByRole('button', { name: 'Add dish' }))
    expect(await within(dialog).findByText('Enter the dish name.')).toBeInTheDocument()
    expect(within(dialog).getByText('Enter the price in rupees.')).toBeInTheDocument()
    expect(within(dialog).getByText('Choose veg, non-veg or egg.')).toBeInTheDocument()

    await user.type(within(dialog).getByLabelText(/^Dish name/), 'Paneer Biryani')
    await user.type(within(dialog).getByLabelText(/^Price/), '219.999')
    await user.click(within(dialog).getByRole('radio', { name: /^vegetarian/i }))
    await user.click(within(dialog).getByRole('button', { name: 'Add dish' }))
    expect(await within(dialog).findByText('Use at most 2 decimal places.')).toBeInTheDocument()
    expect(api.callsTo('POST /restaurant/menu/items')).toHaveLength(0)

    await user.clear(within(dialog).getByLabelText(/^Price/))
    await user.type(within(dialog).getByLabelText(/^Price/), '219.50')
    await user.click(within(dialog).getByRole('button', { name: 'Add dish' }))

    expect(await screen.findByText('Paneer Biryani added to your menu.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.callsTo('POST /restaurant/menu/items')[0]?.body).toEqual({
      categoryId: 'mc1',
      name: 'Paneer Biryani',
      description: null,
      price: 219.5,
      foodType: 'VEG',
      imageUrl: null,
      available: true,
      sortOrder: 0,
    })
    expect(await within(biryani).findByText('₹219.50')).toBeInTheDocument()

    const availability = within(biryani).getByRole('switch', { name: 'Chicken Biryani available' })
    expect(availability).toHaveAttribute('aria-checked', 'true')
    await user.click(availability)

    expect(await screen.findByText('Chicken Biryani is marked sold out.')).toBeInTheDocument()
    expect(availability).toHaveAttribute('aria-checked', 'false')
    expect(api.callsTo('PATCH /restaurant/menu/items/:id/availability')[0]?.pathParams.id).toBe('i1')
    expect(api.callsTo('PATCH /restaurant/menu/items/:id/availability')[0]?.body).toEqual({ available: false })
    const chickenRow = within(biryani).getByText('Chicken Biryani').closest('li')
    expect(within(chickenRow).getAllByText('Sold out').length).toBeGreaterThan(0)
  })

  it('explains a 409 when deleting a category that still has dishes, and pauses orders', async () => {
    const user = userEvent.setup()
    signInAs(['RESTAURANT_OWNER'], { restaurantId: 'r1' })
    const api = menuApi(menu())
    renderApp('/restaurant/menu')

    await user.click(await screen.findByRole('button', { name: 'Delete category Biryani' }))
    const confirm = await screen.findByRole('dialog', { name: 'Delete Biryani?' })
    await user.click(within(confirm).getByRole('button', { name: 'Delete category' }))
    expect(await within(confirm).findByText(/still has dishes/i)).toBeInTheDocument()
    await user.click(within(confirm).getByRole('button', { name: 'Cancel' }))

    const accepting = screen.getByRole('switch', { name: 'Accepting orders' })
    expect(accepting).toHaveAttribute('aria-checked', 'true')
    await user.click(accepting)
    expect(await screen.findByText('New orders are paused.')).toBeInTheDocument()
    expect(accepting).toHaveAttribute('aria-checked', 'false')
    expect(api.callsTo('PUT /restaurant/accepting-orders')[0]?.body).toEqual({ acceptingOrders: false })
  })

  it('shows an overview and narrows the dishes by search, availability and category', async () => {
    const user = userEvent.setup()
    signInAs(['RESTAURANT_OWNER'], { restaurantId: 'r1' })
    menuApi(
      menu({
        itemCount: 3,
        categories: [
          {
            id: 'mc1',
            name: 'Biryani',
            description: null,
            sortOrder: 0,
            items: [
              chickenBiryani(),
              chickenBiryani({ id: 'i2', name: 'Mutton Biryani', description: null, available: false }),
            ],
          },
          {
            id: 'mc2',
            name: 'Desserts',
            description: null,
            sortOrder: 10,
            items: [
              chickenBiryani({
                id: 'i3',
                categoryId: 'mc2',
                name: 'Gulab Jamun',
                description: 'Two warm pieces',
                foodType: 'VEG',
                price: 90,
              }),
            ],
          },
        ],
      }),
    )
    renderApp('/restaurant/menu')

    await screen.findByRole('region', { name: 'Biryani' })
    const overview = screen.getByLabelText('Menu overview')
    expect(within(overview).getByText('Dishes').nextElementSibling).toHaveTextContent('3')
    expect(within(overview).getByText('1 sold out')).toBeInTheDocument()
    expect(within(overview).getByText('Open')).toBeInTheDocument()

    // Search matches the name or the description.
    await user.type(screen.getByRole('searchbox', { name: 'Search dishes' }), 'warm')
    expect(screen.getByRole('region', { name: 'Desserts' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Biryani' })).not.toBeInTheDocument()
    await user.clear(screen.getByRole('searchbox', { name: 'Search dishes' }))

    const show = screen.getByRole('group', { name: 'Show dishes' })
    await user.click(within(show).getByRole('button', { name: 'Sold out' }))
    const biryani = screen.getByRole('region', { name: 'Biryani' })
    expect(within(biryani).getByText('Mutton Biryani')).toBeInTheDocument()
    expect(within(biryani).queryByText('Chicken Biryani')).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Desserts' })).not.toBeInTheDocument()
    await user.click(within(show).getByRole('button', { name: 'All' }))

    const categories = screen.getByRole('navigation', { name: 'Menu categories' })
    await user.click(within(categories).getByRole('button', { name: /^Desserts/ }))
    expect(within(categories).getByRole('button', { name: /^Desserts/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('region', { name: 'Desserts' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Biryani' })).not.toBeInTheDocument()

    await user.type(screen.getByRole('searchbox', { name: 'Search dishes' }), 'pizza')
    expect(screen.getByText('No dishes match')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(screen.getByRole('region', { name: 'Biryani' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Desserts' })).toBeInTheDocument()
  })

  it('lets owners prepare the menu before approval, and makes it read-only while suspended', async () => {
    signInAs(['RESTAURANT_OWNER'])
    menuApi(menu({ restaurantStatus: 'PENDING_APPROVAL' }))
    const { unmount } = renderApp('/restaurant/menu')

    expect(await screen.findByText('Your menu goes live when your restaurant is approved')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add category' })).toBeEnabled()
    unmount()

    menuApi(menu({ restaurantStatus: 'SUSPENDED' }))
    renderApp('/restaurant/menu')
    expect(await screen.findByText('Your restaurant is suspended')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add category' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit Chicken Biryani' })).not.toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Chicken Biryani available' })).toBeDisabled()
    expect(screen.getByRole('switch', { name: 'Accepting orders' })).toBeDisabled()
  })
})
