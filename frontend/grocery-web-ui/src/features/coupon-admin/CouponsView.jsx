import { useState } from 'react'
import { ApiError, errorMessage } from '../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../shared/api/types'
import { useToast } from '../../shared/components/toast/useToast'
import {
  ActionMenu,
  Avatar,
  Badge,
  Button,
  DataTable,
  EmptyState,
  ErrorState,
  FilterBar,
  PageHeader,
  Pagination,
  Spinner,
  StatCard,
  TextField,
} from '../../shared/components/ui'
import { ExportButton } from '../../shared/export'
import { useDebouncedValue } from '../../shared/hooks/useDebouncedValue'
import { useNow } from '../../shared/hooks/useNow'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../shared/icons'
import { formatCount, formatDateTime, formatMoney, formatPrice } from '../../shared/utils/format'
import { CouponFormDialog } from './CouponFormDialog'
import { CouponUsageDrawer } from './CouponUsageDrawer'
import { useCoupons, useSetCouponActive } from './hooks'
import { COUPON_STATE_LABELS, COUPON_STATE_TONES, COUPON_STORE_TYPE_LABELS, couponBenefit, couponState } from './labels'

/** @typedef {import('./api').CouponOwner} CouponOwner */
/** @typedef {import('./api').CouponView} CouponView */

/**
 * @typedef {object} CouponsViewProps
 * @property {CouponOwner} owner
 * @property {boolean} canManage Admins without the permission only read.
 */

/** @type {Record<CouponOwner, string>} */
const DESCRIPTIONS = {
  admin: 'Platform coupons (funded by the platform) and store coupons. Customers use one coupon per cart.',
  vendor: 'Discount codes for your store, funded by you. Public ones are offered to customers at checkout.',
  restaurant: 'Discount codes for your restaurant, funded by you. Public ones are offered to customers at checkout.',
}

/**
 * @typedef {object} CouponStatsProps
 * @property {number} total
 * @property {readonly CouponView[]} rows
 * @property {number} now
 * @property {boolean} searching
 */

/**
 * Headline numbers: how many coupons there are, and what the coupons on this page are doing.
 * @param {CouponStatsProps} props
 */
function CouponStats({ total, rows, now, searching }) {
  const active = rows.filter((coupon) => couponState(coupon, now) === 'ACTIVE').length
  const uses = rows.reduce((sum, coupon) => sum + coupon.usedCount, 0)
  const given = rows.reduce((sum, coupon) => sum + coupon.discountGiven, 0)
  return (
    <section aria-label="Coupons summary" className="animate-fade-up motion-reduce:animate-none">
      {/* Two tiles per row on phones; the amount gets a full row there so it never has to wrap. */}
      <dl className="grid grid-cols-2 gap-3 lg:grid-cols-3">
        <StatCard
          label={searching ? 'Coupons found' : 'Coupons'}
          value={formatCount(total)}
          icon="ticket"
          accent="emerald"
        />
        <StatCard
          label="Active on this page"
          value={formatCount(active)}
          hint="Usable right now"
          icon="bolt"
          accent="sky"
        />
        <StatCard
          label="Discount given on this page"
          value={formatMoney(given)}
          hint={`${formatCount(uses)} ${uses === 1 ? 'use' : 'uses'}`}
          icon="savings"
          accent="amber"
          className="col-span-2 lg:col-span-1"
        />
      </dl>
    </section>
  )
}

/**
 * `/admin/coupons`, `/vendor/coupons`, `/restaurant/coupons` (Phase 12 §2) — the coupons with their state (active,
 * paused, scheduled, expired, used up), uses and discount given; create, edit, pause / resume and the usage.
 */
/** Columns the server can order the export by (its sort options use the same keys). */
const EXPORT_SORTS = ['code', 'validity']

/** @type {Record<CouponsViewProps['owner'], string>} */
const EXPORT_PATHS = {
  admin: '/admin/coupons/export',
  vendor: '/vendor/coupons/export',
  restaurant: '/restaurant/coupons/export',
}

/** @param {CouponsViewProps} props */
export function CouponsView({ owner, canManage }) {
  const toast = useToast()
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const queryText = searchParams.get('q') ?? ''
  const q = useDebouncedValue(queryText.trim(), 300)
  const page = pageFromParam(searchParams.get('page'))
  const coupons = useCoupons(owner, { q: q || undefined, page, size: DEFAULT_PAGE_SIZE })
  const toggle = useSetCouponActive(owner)
  const now = useNow(60_000)
  const [editing, setEditing] = useState(null)
  const [usageOf, setUsageOf] = useState(null)
  const [sort, setSort] = useState(null)

  function setActive(coupon, active) {
    toggle.mutate(
      { id: coupon.id, active },
      {
        onSuccess: () => toast.success(active ? `${coupon.code} resumed` : `${coupon.code} paused`),
        onError: (error) =>
          toast.error(`Could not ${active ? 'resume' : 'pause'} ${coupon.code}`, { description: errorMessage(error) }),
      },
    )
  }

  const columns = [
    {
      key: 'code',
      header: 'Coupon',
      sortValue: (coupon) => coupon.code,
      cell: (coupon) => (
        <div className="flex min-w-0 items-center gap-3">
          <span
            aria-hidden="true"
            className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-violet-50 to-fuchsia-50 ring-1 ring-violet-100"
          >
            <Icon name="ticket" className="size-5" />
          </span>
          <div className="min-w-0">
            {/* The code looks like a tear-off coupon: dashed border, monospace. */}
            <p className="inline-block rounded-md border border-dashed border-emerald-300 bg-emerald-50/70 px-1.5 py-0.5 font-mono text-[13px] font-semibold tracking-wide text-emerald-900">
              {coupon.code}
            </p>
            <p className="mt-0.5 text-xs text-slate-500">{coupon.name}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'benefit',
      header: 'Discount',
      cell: (coupon) => (
        <div className="min-w-0">
          <p className="font-medium text-slate-900">{couponBenefit(coupon)}</p>
          {coupon.minOrderSubtotal > 0 && (
            <p className="text-xs text-slate-500">Min. order {formatPrice(coupon.minOrderSubtotal)}</p>
          )}
        </div>
      ),
    },
    ...(owner === 'admin'
      ? [
          {
            key: 'scope',
            header: 'Scope',
            hideOnMobile: true,
            sortValue: (coupon) =>
              coupon.scope === 'STORE' ? `Store ${coupon.storeName ?? ''}` : `Platform ${coupon.storeType ?? ''}`,
            cell: (coupon) =>
              coupon.scope === 'STORE' ? (
                <span className="flex min-w-0 items-center gap-2">
                  <Avatar name={coupon.storeName ?? 'Store'} size="sm" />
                  <span>Store{coupon.storeName ? `: ${coupon.storeName}` : ''}</span>
                </span>
              ) : (
                <span className="flex min-w-0 items-center gap-2">
                  <span
                    aria-hidden="true"
                    className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-sky-50 to-blue-50 ring-1 ring-sky-100"
                  >
                    <Icon name="globe" className="size-4" />
                  </span>
                  <span>Platform · {COUPON_STORE_TYPE_LABELS[coupon.storeType ?? 'ANY'] ?? coupon.storeType}</span>
                </span>
              ),
          },
        ]
      : []),
    {
      key: 'validity',
      header: 'Valid',
      hideOnMobile: true,
      // Sorted by the end date: which coupons run out first.
      sortValue: (coupon) => coupon.validUntil,
      cell: (coupon) => (
        <span className="text-xs whitespace-nowrap text-slate-600">
          {formatDateTime(coupon.validFrom)}
          <br />
          to {formatDateTime(coupon.validUntil)}
        </span>
      ),
    },
    {
      key: 'usage',
      header: 'Used',
      className: 'text-right',
      sortValue: (coupon) => coupon.usedCount,
      cell: (coupon) => (
        <div className="whitespace-nowrap tabular-nums">
          <p className="font-medium text-slate-900">
            {formatCount(coupon.usedCount)}
            {coupon.totalUsageLimit !== null ? ` / ${formatCount(coupon.totalUsageLimit)}` : ''}
          </p>
          {/* How much of the usage limit is gone (decorative: the numbers above say it). */}
          {coupon.totalUsageLimit !== null && coupon.totalUsageLimit > 0 && (
            <span
              aria-hidden="true"
              className="mt-1 ml-auto block h-1.5 w-20 overflow-hidden rounded-full bg-slate-100"
            >
              <span
                className="block h-full rounded-full bg-gradient-to-r from-emerald-400 to-teal-500"
                style={{ width: `${Math.min(100, (coupon.usedCount / coupon.totalUsageLimit) * 100)}%` }}
              />
            </span>
          )}
          <p className="mt-0.5 text-xs text-slate-500">{formatMoney(coupon.discountGiven)}</p>
        </div>
      ),
    },
    {
      key: 'state',
      header: 'Status',
      sortValue: (coupon) => COUPON_STATE_LABELS[couponState(coupon, now)],
      cell: (coupon) => {
        const state = couponState(coupon, now)
        return (
          <span className="flex flex-col items-start gap-1">
            <Badge tone={COUPON_STATE_TONES[state]} dot>
              {COUPON_STATE_LABELS[state]}
            </Badge>
            {!coupon.public && <Badge tone="gray">Unlisted</Badge>}
          </span>
        )
      },
    },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      className: 'text-right',
      cell: (coupon) => (
        <div className="flex justify-end">
          {canManage ? (
            // Several actions: one "⋯" menu per row.
            <ActionMenu
              label={`Actions for ${coupon.code}`}
              items={[
                { label: 'View usage', icon: 'chart', onSelect: () => setUsageOf(coupon) },
                { label: 'Edit', icon: 'pencil', onSelect: () => setEditing(coupon) },
                {
                  label: coupon.active ? 'Pause' : 'Resume',
                  icon: coupon.active ? 'pauseCircle' : 'refresh',
                  disabled: toggle.isPending && toggle.variables?.id === coupon.id,
                  onSelect: () => setActive(coupon, !coupon.active),
                },
              ]}
            />
          ) : (
            // Read-only roles have a single action: keep it a visible button.
            <Button
              size="sm"
              variant="secondary"
              aria-label={`Usage of ${coupon.code}`}
              onClick={() => setUsageOf(coupon)}
            >
              <Icon name="chart" className="size-4" />
              Usage
            </Button>
          )}
        </div>
      ),
    },
  ]

  return (
    <div className="space-y-5">
      <PageHeader
        icon="ticket"
        title="Coupons"
        description={DESCRIPTIONS[owner]}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <ExportButton
              path={EXPORT_PATHS[owner]}
              label="coupons"
              params={{ q: q || undefined }}
              total={coupons.data?.totalElements}
              sort={sort}
              sortable={EXPORT_SORTS}
            />
            {canManage && (
              <Button onClick={() => setEditing('new')}>
                <Icon name="plus" className="size-4" />
                New coupon
              </Button>
            )}
          </div>
        }
      />
      <FilterBar activeCount={queryText.trim() ? 1 : 0} onClear={() => updateParams({ q: null, page: null })}>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <TextField
            label="Search"
            type="search"
            value={queryText}
            placeholder="Code or name"
            autoComplete="off"
            onChange={(event) => updateParams({ q: event.target.value, page: null }, { replace: true })}
          />
        </div>
      </FilterBar>
      {!canManage && (
        <p className="flex items-center gap-2 text-sm text-slate-500">
          <Icon name="info" className="size-4" />
          Your role can view coupons; finance and operations admins manage them.
        </p>
      )}

      {coupons.data && (
        <CouponStats total={coupons.data.totalElements} rows={coupons.data.content} now={now} searching={Boolean(q)} />
      )}

      {coupons.isPending ? (
        <div className="flex justify-center rounded-2xl border border-slate-200/80 bg-white py-12 shadow-sm">
          <Spinner size="lg" label="Loading coupons" />
        </div>
      ) : coupons.isError ? (
        <ErrorState
          title="Could not load coupons"
          message={coupons.error.message}
          correlationId={coupons.error instanceof ApiError ? coupons.error.correlationId : null}
          onRetry={() => void coupons.refetch()}
        />
      ) : (
        <div>
          <DataTable
            caption="Coupons"
            columns={columns}
            rows={coupons.data.content}
            getRowKey={(coupon) => coupon.id}
            sort={sort}
            onSortChange={setSort}
            fetching={coupons.isFetching && coupons.isPlaceholderData}
            empty={
              <EmptyState
                title={q ? 'No coupon matches' : 'No coupons yet'}
                description={q ? 'Try another code or name.' : 'Create a coupon to give customers a discount.'}
                action={
                  canManage && !q ? (
                    <Button onClick={() => setEditing('new')}>
                      <Icon name="plus" className="size-4" />
                      New coupon
                    </Button>
                  ) : undefined
                }
              />
            }
          />
          <Pagination
            page={coupons.data.page}
            size={coupons.data.size}
            totalElements={coupons.data.totalElements}
            totalPages={coupons.data.totalPages}
            disabled={coupons.isFetching}
            onPageChange={(next) => updateParams({ page: pageToParam(next) })}
          />
        </div>
      )}

      <CouponFormDialog
        owner={owner}
        open={editing !== null}
        coupon={editing === 'new' ? null : editing}
        onClose={() => setEditing(null)}
      />
      {usageOf && <CouponUsageDrawer owner={owner} coupon={usageOf} onClose={() => setUsageOf(null)} />}
    </div>
  )
}
