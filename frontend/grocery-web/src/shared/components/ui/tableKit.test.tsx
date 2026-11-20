import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ActionMenu } from './ActionMenu'
import { type Column, DataTable } from './DataTable'
import { FilterBar } from './FilterBar'

interface Row {
  id: string
  name: string
  total: number | null
}

const ROWS: Row[] = [
  { id: 'a', name: 'Banana Mart', total: 120 },
  { id: 'b', name: 'apple store', total: null },
  { id: 'c', name: 'Cherry Foods', total: 75.5 },
]

const COLUMNS: Column<Row>[] = [
  { key: 'name', header: 'Name', cell: (row) => row.name, sortValue: (row) => row.name },
  { key: 'total', header: 'Total', cell: (row) => row.total ?? '—', sortValue: (row) => row.total },
  { key: 'actions', header: 'Actions', cell: () => null },
]

function names(): string[] {
  return within(screen.getByRole('table')).getAllByRole('row').slice(1).map((row) => row.querySelector('td')?.textContent ?? '')
}

describe('DataTable sorting', () => {
  it('sorts a column ascending, then descending, then back to the loaded order; empty values stay last', async () => {
    const user = userEvent.setup()
    render(<DataTable caption="Stores" columns={COLUMNS} rows={ROWS} getRowKey={(row) => row.id} />)

    const name = screen.getByRole('columnheader', { name: /name/i })
    expect(name).toHaveAttribute('aria-sort', 'none')
    expect(screen.getByRole('columnheader', { name: 'Actions' })).not.toHaveAttribute('aria-sort')

    await user.click(within(name).getByRole('button', { name: /name/i }))
    expect(name).toHaveAttribute('aria-sort', 'ascending')
    expect(names()).toEqual(['apple store', 'Banana Mart', 'Cherry Foods'])

    await user.click(within(name).getByRole('button', { name: /name/i }))
    expect(name).toHaveAttribute('aria-sort', 'descending')
    expect(names()).toEqual(['Cherry Foods', 'Banana Mart', 'apple store'])

    await user.click(within(name).getByRole('button', { name: /name/i }))
    expect(name).toHaveAttribute('aria-sort', 'none')
    expect(names()).toEqual(['Banana Mart', 'apple store', 'Cherry Foods'])

    const total = screen.getByRole('columnheader', { name: /total/i })
    await user.click(within(total).getByRole('button'))
    expect(names()).toEqual(['Cherry Foods', 'Banana Mart', 'apple store'])
    await user.click(within(total).getByRole('button'))
    expect(names()).toEqual(['Banana Mart', 'Cherry Foods', 'apple store'])
  })
})

describe('ActionMenu', () => {
  it('opens on the first item, moves with the arrow keys, runs an item and returns focus to its button', async () => {
    const user = userEvent.setup()
    const view = vi.fn()
    const suspend = vi.fn()
    render(
      <ActionMenu
        label="Actions for Banana Mart"
        items={[
          { label: 'View details', icon: 'eye', onSelect: view },
          { label: 'Archive', onSelect: vi.fn(), disabled: true },
          { label: 'Suspend', tone: 'danger', onSelect: suspend },
        ]}
      />,
    )

    const button = screen.getByRole('button', { name: 'Actions for Banana Mart' })
    expect(button).toHaveAttribute('aria-expanded', 'false')
    await user.click(button)
    const menu = await screen.findByRole('menu', { name: 'Actions for Banana Mart' })
    expect(button).toHaveAttribute('aria-expanded', 'true')
    expect(within(menu).getByRole('menuitem', { name: 'View details' })).toHaveFocus()

    // The disabled item is skipped.
    await user.keyboard('{ArrowDown}')
    expect(within(menu).getByRole('menuitem', { name: 'Suspend' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(suspend).toHaveBeenCalledOnce()
    expect(view).not.toHaveBeenCalled()
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(button).toHaveFocus()

    // Escape closes without running anything.
    await user.keyboard('{ArrowDown}')
    expect(await screen.findByRole('menu')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(button).toHaveFocus()
    expect(view).not.toHaveBeenCalled()
  })

  it('closes on a click elsewhere', async () => {
    const user = userEvent.setup()
    render(
      <div>
        <ActionMenu label="Actions" items={[{ label: 'View', onSelect: vi.fn() }]} />
        <p>Outside</p>
      </div>,
    )
    await user.click(screen.getByRole('button', { name: 'Actions' }))
    expect(await screen.findByRole('menu')).toBeInTheDocument()
    await user.click(screen.getByText('Outside'))
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })
})

describe('FilterBar', () => {
  it('shows how many filters are on and clears them', async () => {
    const user = userEvent.setup()
    const clear = vi.fn()
    const { rerender } = render(
      <FilterBar activeCount={0} onClear={clear}>
        <input aria-label="Search" />
      </FilterBar>,
    )
    const bar = screen.getByRole('region', { name: 'Search and filters' })
    expect(within(bar).getByRole('textbox', { name: 'Search' })).toBeInTheDocument()
    expect(within(bar).queryByRole('button', { name: 'Clear all' })).not.toBeInTheDocument()

    rerender(
      <FilterBar activeCount={2} onClear={clear}>
        <input aria-label="Search" />
      </FilterBar>,
    )
    expect(bar).toHaveTextContent('2 active')
    await user.click(within(bar).getByRole('button', { name: 'Clear all' }))
    expect(clear).toHaveBeenCalledOnce()
  })
})
