import { usePendingPriceRequestCount } from '../../../features/admin-pricing/hooks'
import { PriceRequestQueue } from '../../../features/admin-pricing/PriceRequestQueue'
import {
  isPriceChangeStatus,
  PRICE_CHANGE_STATUS_LABELS,
  PRICE_CHANGE_STATUSES,
} from '../../../features/vendor-pricing/api'
import { Badge, PageHeader, Tabs } from '../../../shared/components/ui'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'

/** @typedef {import('../../../features/vendor-pricing/api').PriceChangeStatus} PriceChangeStatus */
/** @typedef {import('../../../shared/components/ui').TabItem} TabItem */

function PendingLabel() {
  const count = usePendingPriceRequestCount()
  return (
    <>
      {PRICE_CHANGE_STATUS_LABELS.PENDING}
      {count.data !== undefined && count.data > 0 && <Badge tone="amber">{count.data}</Badge>}
    </>
  )
}

/** @type {readonly TabItem<PriceChangeStatus>[]} */
const TABS = PRICE_CHANGE_STATUSES.map((status) => ({
  id: status,
  label: status === 'PENDING' ? <PendingLabel /> : PRICE_CHANGE_STATUS_LABELS[status],
}))

/** `/admin/price-approvals` — vendors' price changes above the approval threshold (`?status=` and `?page=` in the URL). */
export function PriceApprovalsPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const statusParam = searchParams.get('status')
  const status = isPriceChangeStatus(statusParam) ? statusParam : 'PENDING'
  const page = pageFromParam(searchParams.get('page'))

  return (
    <div>
      <PageHeader
        icon="tag"
        title="Price approvals"
        description="Selling-price changes bigger than the approval threshold wait here. The live price stays until a change is approved."
      />
      <Tabs
        label="Request status"
        tabs={TABS}
        value={status}
        onChange={(next) => updateParams({ status: next === 'PENDING' ? null : next, page: null }, { replace: true })}
      >
        <PriceRequestQueue
          key={status}
          status={status}
          page={page}
          onPageChange={(next) => updateParams({ page: pageToParam(next) })}
        />
      </Tabs>
    </div>
  )
}
