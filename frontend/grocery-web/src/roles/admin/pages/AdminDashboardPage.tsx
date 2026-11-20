import { OrderStatsPanel } from '../../../features/admin-orders/OrderStatsPanel'
import { usePendingPriceRequestCount } from '../../../features/admin-pricing/hooks'
import { InsightsCard } from '../../../features/ai/InsightsCard'
import { LatestNotificationsCard } from '../../../features/notifications/LatestNotificationsCard'
import { PipelineCard, StuckOrdersCard } from '../../../features/operations/OperationsCards'
import { usePendingCount } from '../../../features/stakeholders/admin/hooks'
import type { StakeholderKind } from '../../../features/stakeholders/admin/kind'
import type { StakeholderLifecycle } from '../../../features/stakeholders/types'
import { BackendHealthCard } from '../../../features/system/BackendHealthCard'
import { roleLabel } from '../../../shared/auth/roles'
import { useAuth } from '../../../shared/auth/useAuth'
import { LinkCard, PageHeader, Spinner } from '../../../shared/components/ui'
import type { IconName } from '../../../shared/icons'
import { partnerKind, restaurantKind, vendorKind } from '../stakeholderKinds'

function PendingTile<T extends StakeholderLifecycle>({ kind, icon, accent }: { kind: StakeholderKind<T>; icon: IconName; accent: 'emerald' | 'amber' | 'sky' }) {
  const count = usePendingCount(kind)
  return (
    <LinkCard
      to={`/admin/approvals?type=${kind.slug}`}
      icon={icon}
      accent={accent}
      title={`${kind.plural} awaiting approval`}
      value={count.isPending ? <Spinner size="sm" label="Loading" /> : count.isError ? '—' : count.data}
      cta="Review"
    />
  )
}

function PendingPricesTile() {
  const count = usePendingPriceRequestCount()
  return (
    <LinkCard
      to="/admin/price-approvals"
      icon="tag"
      accent="violet"
      title="Price changes awaiting approval"
      value={count.isPending ? <Spinner size="sm" label="Loading" /> : count.isError ? '—' : count.data}
      cta="Review"
    />
  )
}

export function AdminDashboardPage() {
  const { user } = useAuth()
  const roles = user?.roles.map(roleLabel).join(', ')

  return (
    <div className="space-y-6">
      <PageHeader
        icon="grid"
        title="Admin dashboard"
        description={`Signed in as ${roles ?? 'admin'}. Platform health, approvals and business monitoring.`}
      />
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <PendingTile kind={vendorKind} icon="store" accent="emerald" />
        <PendingTile kind={restaurantKind} icon="cloche" accent="amber" />
        <PendingTile kind={partnerKind} icon="scooter" accent="sky" />
        <PendingPricesTile />
      </div>
      <LinkCard to="/admin/catalog" icon="basket" accent="teal" title="Catalog" description="Category tree, every vendor's products and the search index." cta="Open catalog" />
      <OrderStatsPanel />
      {/* Phase 11: a structured report by the business assistant (or by rules when the model is down), on request. */}
      <InsightsCard />
      {/* Phase 10: stuck orders and the event pipeline (connectors, dead letters) — details on the Operations page. */}
      <div className="grid gap-4 sm:grid-cols-2">
        <StuckOrdersCard />
        <PipelineCard />
      </div>
      <LatestNotificationsCard
        title="Live activity"
        description="Approval requests, failed refunds, price changes and stuck orders for your role, as they happen."
      />
      <BackendHealthCard />
      <div className="grid gap-4 sm:grid-cols-2">
        <LinkCard
          to="/admin/deliveries"
          icon="truck"
          accent="sky"
          title="Deliveries"
          description="Live assignment monitor: deliveries without a partner, offers and reassignment."
          cta="Open deliveries"
        />
        <LinkCard
          to="/admin/ai/approvals"
          icon="sparkleQuestion"
          accent="violet"
          title="AI approvals"
          description="Refunds, cancellations and suspensions the assistant proposed wait here for a decision."
          cta="Open AI approvals"
        />
      </div>
      {/* Phase 12: reporting, moderation and returns. */}
      <div className="grid gap-4 sm:grid-cols-3">
        {(
          [
            { to: '/admin/reports', icon: 'chart', accent: 'teal', title: 'Reports', text: 'Sales, deliveries, promotions, ratings and purchasing, with CSV downloads.', link: 'Open reports' },
            { to: '/admin/reviews', icon: 'starFlag', accent: 'amber', title: 'Review moderation', text: 'Flagged reviews stay hidden until someone publishes or rejects them.', link: 'Moderate reviews' },
            { to: '/admin/returns', icon: 'returnAlert', accent: 'rose', title: 'Returns', text: 'Return requests the stores did not decide in time are escalated here.', link: 'Open returns' },
          ] as const
        ).map((item) => (
          <LinkCard key={item.to} to={item.to} icon={item.icon} accent={item.accent} title={item.title} description={item.text} cta={item.link} />
        ))}
      </div>
    </div>
  )
}
