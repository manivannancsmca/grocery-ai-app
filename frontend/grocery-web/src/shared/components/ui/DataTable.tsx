import { type ReactNode, useMemo, useState } from 'react'
import { Icon } from '../../icons'
import { cn } from '../../utils/cn'
import { Spinner } from './Spinner'
import { TABLE_HEAD_CLASS, TABLE_HEAD_ROW_CLASS } from './tableStyles'

export interface Column<T> {
  key: string
  header: ReactNode
  cell: (row: T) => ReactNode
  className?: string
  /** Hide on small screens to keep the table readable on phones. */
  hideOnMobile?: boolean
  /**
   * Makes the column sortable: the value rows are ordered by (numbers numerically, text alphabetically, ISO dates as
   * text). Sorting reorders the rows on screen (the current page); empty values always come last.
   */
  sortValue?: (row: T) => string | number | null | undefined
}

interface DataTableProps<T> {
  columns: readonly Column<T>[]
  rows: readonly T[]
  getRowKey: (row: T) => string
  /** Accessible name of the table. */
  caption: string
  /** Shown (in place of rows) when `rows` is empty. */
  empty?: ReactNode
  /** Dims the rows while a new page loads. */
  fetching?: boolean
  /** Extra classes for a row (e.g. highlighting blocked attempts). */
  rowClassName?: (row: T) => string | undefined
  /**
   * The column sort, when the page needs to know it (e.g. to export in the same order): pass both `sort` and
   * `onSortChange`. Without them the table keeps its own sort.
   */
  sort?: TableSort | null
  onSortChange?: (sort: TableSort | null) => void
}

type SortDirection = 'ascending' | 'descending'

/** A table's column sort: the column's `key` and the direction. */
export interface TableSort {
  key: string
  direction: SortDirection
}

const collator = new Intl.Collator('en-IN', { numeric: true, sensitivity: 'base' })

function compareValues(a: string | number | null | undefined, b: string | number | null | undefined): number {
  const aEmpty = a === null || a === undefined || a === ''
  const bEmpty = b === null || b === undefined || b === ''
  if (aEmpty || bEmpty) return aEmpty === bEmpty ? 0 : aEmpty ? 1 : -1
  if (typeof a === 'number' && typeof b === 'number') return a - b
  return collator.compare(String(a), String(b))
}

/** Click: ascending → descending → as loaded. */
function nextSort(current: TableSort | null, key: string): TableSort | null {
  if (current?.key !== key) return { key, direction: 'ascending' }
  return current.direction === 'ascending' ? { key, direction: 'descending' } : null
}

/**
 * Responsive data table: horizontal scroll on narrow screens, optional mobile-hidden columns, sortable columns (give a
 * column a `sortValue`), a loading bar on refetch and a row highlight on hover.
 */
export function DataTable<T>({
  columns,
  rows,
  getRowKey,
  caption,
  empty,
  fetching,
  rowClassName,
  sort: controlledSort,
  onSortChange,
}: DataTableProps<T>) {
  const [ownSort, setOwnSort] = useState<TableSort | null>(null)
  const sort = onSortChange ? (controlledSort ?? null) : ownSort
  const toggleSort = (key: string) => {
    const next = nextSort(sort, key)
    if (onSortChange) onSortChange(next)
    else setOwnSort(next)
  }

  const sortedRows = useMemo(() => {
    const column = sort ? columns.find((candidate) => candidate.key === sort.key) : undefined
    if (!sort || !column?.sortValue) return rows
    const value = column.sortValue
    const sign = sort.direction === 'ascending' ? 1 : -1
    return [...rows].sort((a, b) => {
      const aValue = value(a)
      const bValue = value(b)
      const aEmpty = aValue === null || aValue === undefined || aValue === ''
      const bEmpty = bValue === null || bValue === undefined || bValue === ''
      // Empty values stay at the end whichever way the column is sorted.
      if (aEmpty || bEmpty) return compareValues(aValue, bValue)
      return sign * compareValues(aValue, bValue)
    })
  }, [columns, rows, sort])

  if (rows.length === 0 && empty) return <>{empty}</>

  return (
    <div className="animate-fade-in relative overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm shadow-slate-900/[0.04]">
      {fetching && (
        <>
          <div aria-hidden="true" className="absolute inset-x-0 top-0 z-10 h-0.5 overflow-hidden bg-white/20">
            <div className="animate-shimmer h-full w-full bg-[linear-gradient(90deg,transparent,#a7f3d0,transparent)] bg-[length:50%_100%] bg-no-repeat" />
          </div>
          <div className="absolute top-2.5 right-3 z-10">
            <Spinner size="sm" label="Loading" className="[&_svg]:text-white" />
          </div>
        </>
      )}
      <div className="overflow-x-auto">
        <table className={cn('min-w-full text-sm transition-opacity', fetching && 'opacity-60')}>
          <caption className="sr-only">{caption}</caption>
          <thead className={TABLE_HEAD_CLASS}>
            <tr className={TABLE_HEAD_ROW_CLASS}>
              {columns.map((column) => {
                const active = sort?.key === column.key ? sort.direction : undefined
                return (
                  <th
                    key={column.key}
                    scope="col"
                    aria-sort={column.sortValue ? (active ?? 'none') : undefined}
                    // The column's classes are shared with its cells; the header text stays white on the band.
                    className={cn('px-4 py-3', column.hideOnMobile && 'hidden md:table-cell', column.className, 'text-white! print:text-slate-700!')}
                  >
                    {column.sortValue ? (
                      <button
                        type="button"
                        onClick={() => toggleSort(column.key)}
                        className="group/sort -mx-1.5 inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 tracking-wider uppercase transition-colors hover:bg-white/15 focus-visible:outline-2 focus-visible:outline-white"
                      >
                        {column.header}
                        <Icon
                          name={active === 'ascending' ? 'sortUp' : active === 'descending' ? 'sortDown' : 'sortBoth'}
                          className={cn('size-3.5 transition-opacity', active ? 'opacity-100' : 'opacity-60 group-hover/sort:opacity-100')}
                        />
                      </button>
                    ) : (
                      column.header
                    )}
                  </th>
                )
              })}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {sortedRows.map((row) => {
              // A row's own highlight (e.g. a failed email) replaces the zebra stripe and the hover tint.
              const highlight = rowClassName?.(row)
              return (
                <tr
                  key={getRowKey(row)}
                  className={cn(
                    'transition-colors duration-150 [&>td:first-child]:shadow-[inset_3px_0_0_transparent] hover:[&>td:first-child]:shadow-[inset_3px_0_0_#10b981]',
                    highlight ?? 'even:bg-slate-50/40 hover:bg-emerald-50/50',
                  )}
                >
                  {columns.map((column) => (
                    <td
                      key={column.key}
                      className={cn('px-4 py-3.5 align-middle text-slate-700', column.hideOnMobile && 'hidden md:table-cell', column.className)}
                    >
                      {column.cell(row)}
                    </td>
                  ))}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
