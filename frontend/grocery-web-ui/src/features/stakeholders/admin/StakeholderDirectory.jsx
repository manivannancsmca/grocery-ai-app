import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import {
  Button,
  FilterBar,
  PageHeader,
  SelectField,
  Skeleton,
  StatCard,
  TextField,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { Icon } from '../../../shared/icons'
import { formatDateTime } from '../../../shared/utils/format'
import { isStakeholderStatus, STAKEHOLDER_STATUSES, STATUS_LABELS } from '../types'
import { useStakeholderList, useStatusCount } from './hooks'
import { StakeholderDetailDrawer } from './StakeholderDetailDrawer'
import { StakeholderTable } from './StakeholderTable'

/** @typedef {import('../../../shared/components/ui').StatAccent} StatAccent */
/** @typedef {import('../../../shared/icons').IconName} IconName */
/** @typedef {import('../types').StakeholderLifecycle} StakeholderLifecycle */
/** @typedef {import('../types').StakeholderStatus} StakeholderStatus */
/** @typedef {import('./kind').StakeholderKind} StakeholderKind */

/**
 * @param {string | null} value
 * @returns {number}
 */
function pageFromParam(value) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed - 1 : 0
}

/**
 * The picture of each stakeholder type (the same as its menu item).
 * @type {Record<StakeholderKind<StakeholderLifecycle>['slug'], IconName>}
 */
const KIND_ICONS = {
  vendors: 'store',
  restaurants: 'cloche',
  'delivery-partners': 'scooter',
}

/**
 * @template {StakeholderLifecycle} T
 * @typedef {object} SummaryTileProps
 * @property {StakeholderKind<T>} kind
 * @property {StakeholderStatus | undefined} status `undefined` counts every status.
 * @property {string} label
 * @property {IconName} icon
 * @property {StatAccent} accent
 */

/**
 * One headline count. It asks for a one-row page, so only the total travels (and lists refresh it after an action).
 * @template {StakeholderLifecycle} T
 * @param {SummaryTileProps<T>} props
 */
function SummaryTile({ kind, status, label, icon, accent }) {
  const count = useStatusCount(kind, status)
  const value =
    count.data !== undefined ? (
      count.data.toLocaleString('en-IN')
    ) : count.isPending ? (
      <Skeleton className="my-1 h-6 w-10" />
    ) : (
      '—'
    )
  return <StatCard label={label} value={value} icon={icon} accent={accent} />
}

/**
 * Columns each list's export can be ordered by on the server (the export's sort options use the same keys). The
 * partner list has none: its order depends on the status filter (the review queue oldest first).
 * @type {Record<StakeholderKind<StakeholderLifecycle>['slug'], readonly string[]>}
 */
const EXPORT_SORTS = {
  vendors: [],
  restaurants: [],
  'delivery-partners': [],
}

/**
 * @template {StakeholderLifecycle} T
 * @typedef {object} StakeholderDirectoryProps
 * @property {StakeholderKind<T>} kind
 * @property {string} title The page heading (rendered here, so its Export button sees the filters).
 * @property {string} description
 */

/**
 * Searchable, filterable list of one stakeholder type. Filters live in the URL
 * (`?status=APPROVED&q=fresh&page=2`), so they survive reloads and can be shared. "Export" downloads every match.
 * @template {StakeholderLifecycle} T
 * @param {StakeholderDirectoryProps<T>} props
 */
export function StakeholderDirectory({ kind, title, description }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const [selected, setSelected] = useState(null)
  const [sort, setSort] = useState(null)

  const statusParam = searchParams.get('status')
  const status = isStakeholderStatus(statusParam) ? statusParam : undefined
  const queryText = searchParams.get('q') ?? ''
  const page = pageFromParam(searchParams.get('page'))
  const debouncedQuery = useDebouncedValue(queryText.trim(), 300)

  const query = useStakeholderList(kind, { status, query: debouncedQuery || undefined, page, size: DEFAULT_PAGE_SIZE })

  function updateParams(changes) {
    setSearchParams(
      (current) => {
        const next = new URLSearchParams(current)
        for (const [key, value] of Object.entries(changes)) {
          if (value === null || value === '') next.delete(key)
          else next.set(key, value)
        }
        return next
      },
      { replace: true },
    )
  }

  // How many filters are on (for the filter card), and how to switch them all off again.
  const activeFilters = (queryText.trim() ? 1 : 0) + (status ? 1 : 0)
  const clearFilters = () => updateParams({ q: null, status: null, page: null })
  const plural = kind.plural.toLowerCase()

  return (
    <div className="space-y-5">
      <PageHeader
        icon={KIND_ICONS[kind.slug]}
        title={title}
        description={description}
        actions={
          <ExportButton
            path={`/admin/${kind.slug}/export`}
            label={plural}
            params={{ status, query: debouncedQuery || undefined }}
            total={query.data?.totalElements}
            sort={sort}
            sortable={EXPORT_SORTS[kind.slug]}
          />
        }
      />
      <section aria-label={`${kind.plural} at a glance`}>
        <dl className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          <SummaryTile
            kind={kind}
            status={undefined}
            label={`All ${plural}`}
            icon={KIND_ICONS[kind.slug]}
            accent="sky"
          />
          <SummaryTile
            kind={kind}
            status="APPROVED"
            label={STATUS_LABELS.APPROVED}
            icon="checkCircle"
            accent="emerald"
          />
          <SummaryTile
            kind={kind}
            status="PENDING_APPROVAL"
            label={STATUS_LABELS.PENDING_APPROVAL}
            icon="hourglass"
            accent="amber"
          />
          <SummaryTile
            kind={kind}
            status="SUSPENDED"
            label={STATUS_LABELS.SUSPENDED}
            icon="pauseCircle"
            accent="violet"
          />
        </dl>
      </section>

      <FilterBar activeCount={activeFilters} onClear={clearFilters}>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <TextField
            label={`Search ${plural}`}
            type="search"
            value={queryText}
            placeholder={kind.searchPlaceholder}
            containerClassName="sm:col-span-2"
            onChange={(event) => updateParams({ q: event.target.value, page: null })}
          />
          <SelectField
            label="Filter by status"
            value={status ?? ''}
            onChange={(event) => updateParams({ status: event.target.value, page: null })}
          >
            <option value="">All statuses</option>
            {STAKEHOLDER_STATUSES.map((value) => (
              <option key={value} value={value}>
                {STATUS_LABELS[value]}
              </option>
            ))}
          </SelectField>
        </div>
      </FilterBar>

      <StakeholderTable
        kind={kind}
        query={query}
        actionLabel="View"
        emptyTitle={`No ${plural} found`}
        emptyDescription={
          status || debouncedQuery
            ? 'Try a different search or status filter.'
            : `${kind.plural} appear here once they sign up and start their onboarding.`
        }
        emptyAction={
          activeFilters > 0 ? (
            <Button variant="secondary" size="sm" onClick={clearFilters}>
              <Icon name="close" className="size-4" />
              Clear filters
            </Button>
          ) : undefined
        }
        onSelect={setSelected}
        sort={sort}
        onSortChange={setSort}
        onPageChange={(next) => updateParams({ page: next > 0 ? String(next + 1) : null })}
        extraColumns={[
          {
            key: 'updated',
            header: 'Last updated',
            hideOnMobile: true,
            sortValue: (item) => item.updatedAt,
            cell: (item) => <span className="whitespace-nowrap">{formatDateTime(item.updatedAt)}</span>,
          },
        ]}
      />

      {selected && (
        <StakeholderDetailDrawer kind={kind} id={selected.id} initial={selected} onClose={() => setSelected(null)} />
      )}
    </div>
  )
}
