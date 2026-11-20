import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

function node(id, name, depth, children = [], overrides = {}) {
  return {
    id,
    parentId: null,
    name,
    description: null,
    imageUrl: null,
    sortOrder: 0,
    active: true,
    depth,
    productCount: 0,
    children,
    ...overrides,
  }
}

const tree = [
  node('c-fv', 'Fruits & Vegetables', 1, [
    node(
      'c-veg',
      'Fresh Vegetables',
      2,
      [node('c-cherry', 'Cherry Tomatoes', 3, [], { parentId: 'c-veg', productCount: 4 })],
      {
        parentId: 'c-fv',
      },
    ),
  ]),
  node('c-dairy', 'Dairy & Bakery', 1, [], { active: false }),
]

function categoriesApi() {
  let categories = tree
  return mockApi({
    'GET /admin/categories': () => ({ status: 200, data: categories }),
    'POST /admin/categories': (request) => {
      const body = request.body
      if (body.name.toLowerCase() === 'fresh vegetables') return problem(409, 'CATEGORY_NAME_TAKEN', 'Name taken')
      const created = node('c-new', body.name, 2, [], { parentId: body.parentId, sortOrder: body.sortOrder })
      categories = categories.map((root) =>
        root.id === body.parentId ? { ...root, children: [...root.children, created] } : root,
      )
      return { status: 201, data: created }
    },
    'DELETE /admin/categories/:id': problem(409, 'CATEGORY_IN_USE', 'Category is in use'),
  })
}

describe('Admin catalog — categories', () => {
  it('creates a sub-category with validation, offering only parents that keep the tree 3 levels deep', async () => {
    const user = userEvent.setup()
    signInAs(['SUPER_ADMIN'])
    const api = categoriesApi()
    renderApp('/admin/catalog')

    const table = await screen.findByRole('table', { name: 'Category tree' })
    expect(within(table).getByText('Cherry Tomatoes')).toBeInTheDocument()
    expect(within(table).getByText('Inactive')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'New category' }))
    const dialog = await screen.findByRole('dialog', { name: 'New category' })

    await user.click(within(dialog).getByRole('button', { name: 'Create category' }))
    expect(await within(dialog).findByText('Enter a category name.')).toBeInTheDocument()

    const parent = within(dialog).getByLabelText('Parent category')
    const options = within(parent)
      .getAllByRole('option')
      .map((option) => option.textContent?.replace(/[\s\u00A0└]+/g, ' ').trim())
    expect(options).toEqual([
      'None — top level',
      'Fruits & Vegetables',
      'Fresh Vegetables',
      'Dairy & Bakery (inactive)',
    ])

    await user.type(within(dialog).getByLabelText(/^Name/), 'Fresh Vegetables')
    await user.selectOptions(parent, 'c-fv')
    await user.click(within(dialog).getByRole('button', { name: 'Create category' }))
    expect(
      await within(dialog).findByText('A category with this name already exists at this level.'),
    ).toBeInTheDocument()

    await user.clear(within(dialog).getByLabelText(/^Name/))
    await user.type(within(dialog).getByLabelText(/^Name/), 'Exotic Fruits')
    await user.clear(within(dialog).getByLabelText(/^Sort order/))
    await user.type(within(dialog).getByLabelText(/^Sort order/), '5')
    await user.click(within(dialog).getByRole('button', { name: 'Create category' }))

    expect(await screen.findByText('Exotic Fruits created.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.callsTo('POST /admin/categories').at(-1)?.body).toEqual({
      name: 'Exotic Fruits',
      parentId: 'c-fv',
      description: null,
      imageUrl: null,
      sortOrder: 5,
    })
    expect(await within(table).findByText('Exotic Fruits')).toBeInTheDocument()
  })

  it('pre-selects the parent for "Add sub-category" and explains a 409 on delete', async () => {
    const user = userEvent.setup()
    signInAs(['OPS_ADMIN'])
    categoriesApi()
    renderApp('/admin/catalog')

    // Row actions live in each row's "⋯" menu.
    await user.click(await screen.findByRole('button', { name: 'Actions for Fresh Vegetables' }))
    await user.click(
      within(await screen.findByRole('menu', { name: 'Actions for Fresh Vegetables' })).getByRole('menuitem', {
        name: 'Add sub-category',
      }),
    )
    const dialog = await screen.findByRole('dialog', { name: 'New category' })
    expect(within(dialog).getByLabelText('Parent category')).toHaveValue('c-veg')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    // Third-level categories cannot have children.
    await user.click(screen.getByRole('button', { name: 'Actions for Cherry Tomatoes' }))
    const menu = await screen.findByRole('menu', { name: 'Actions for Cherry Tomatoes' })
    expect(within(menu).queryByRole('menuitem', { name: 'Add sub-category' })).not.toBeInTheDocument()

    await user.click(within(menu).getByRole('menuitem', { name: 'Delete' }))
    const confirm = await screen.findByRole('dialog', { name: 'Delete Cherry Tomatoes?' })
    await user.click(within(confirm).getByRole('button', { name: 'Delete category' }))
    expect(await within(confirm).findByText(/still has sub-categories or products/i)).toBeInTheDocument()
  })

  it('shows the tree read-only to admin roles without catalog permissions', async () => {
    signInAs(['FINANCE_ADMIN'])
    categoriesApi()
    renderApp('/admin/catalog')

    expect(await screen.findByText('Cherry Tomatoes')).toBeInTheDocument()
    expect(screen.getByText(/can view categories but not change them/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New category' })).not.toBeInTheDocument()
    // No row actions at all (edit, (de)activate, delete live in the rows' "⋯" menus).
    expect(screen.queryByRole('button', { name: /^Actions for / })).not.toBeInTheDocument()
  })
})

describe('Admin catalog — search index', () => {
  it('lists the indexes and republishes the catalog for operations admins', async () => {
    const user = userEvent.setup()
    signInAs(['OPS_ADMIN'])
    const api = mockApi({
      'GET /admin/search/indexes': {
        status: 200,
        data: [
          { alias: 'products', index: 'products_v1', documents: 230, health: 'green' },
          { alias: 'restaurants', index: 'restaurants_v1', documents: 12, health: 'yellow' },
        ],
      },
      'POST /admin/catalog/republish': { status: 200, data: { categories: 14, products: 230 } },
    })
    renderApp('/admin/catalog?tab=search')

    const table = await screen.findByRole('table', { name: 'Search indexes' })
    expect(within(table).getByText('products_v1')).toBeInTheDocument()
    expect(within(table).getByText('yellow')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Republish catalog' }))
    const confirm = await screen.findByRole('dialog', { name: 'Republish the catalog?' })
    await user.click(within(confirm).getByRole('button', { name: 'Republish catalog' }))

    expect(await screen.findByText(/republished 14 categories and 230 products/i)).toBeInTheDocument()
    expect(api.callsTo('POST /admin/catalog/republish')).toHaveLength(1)
  })

  it('hides republishing from roles without permission', async () => {
    signInAs(['SUPPORT'])
    mockApi({ 'GET /admin/search/indexes': { status: 200, data: [] } })
    renderApp('/admin/catalog?tab=search')

    expect(await screen.findByText('No indexes reported')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Republish catalog' })).not.toBeInTheDocument()
  })

  it('republishes prices and stock, and explains the order after an index rebuild', async () => {
    const user = userEvent.setup()
    signInAs(['SUPER_ADMIN'])
    const api = mockApi({
      'GET /admin/search/indexes': {
        status: 200,
        data: [{ alias: 'products', index: 'products_v2', documents: 0, health: 'green' }],
      },
      'POST /admin/pricing/republish': { status: 200, data: { prices: 228 } },
      'POST /admin/inventory/republish': problem(503, 'SERVICE_UNAVAILABLE', 'Inventory service is down'),
    })
    renderApp('/admin/catalog?tab=search')

    expect(await screen.findByRole('table', { name: 'Search indexes' })).toBeInTheDocument()
    expect(screen.getByText(/republish in this order/i)).toHaveTextContent('catalog → prices → stock')

    await user.click(screen.getByRole('button', { name: 'Republish prices' }))
    const prices = await screen.findByRole('dialog', { name: 'Republish prices?' })
    await user.click(within(prices).getByRole('button', { name: 'Republish prices' }))
    expect(await screen.findByText('Republished 228 prices.')).toBeInTheDocument()
    expect(api.callsTo('POST /admin/pricing/republish')).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: 'Republish stock' }))
    const stock = await screen.findByRole('dialog', { name: 'Republish stock?' })
    await user.click(within(stock).getByRole('button', { name: 'Republish stock' }))
    expect(await within(stock).findByText(/republishing failed: inventory service is down/i)).toBeInTheDocument()
    expect(api.callsTo('POST /admin/inventory/republish')).toHaveLength(1)
  })
})
