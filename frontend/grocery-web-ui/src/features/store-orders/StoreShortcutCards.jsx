import { Link } from 'react-router'
import { FeatureCard, TILE_LINK_CLASS } from '../../shared/components/ui'
import { STORE_KIND_CONFIG } from './api'

/** @typedef {import('../../shared/components/ui').StatAccent} StatAccent */
/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').StoreKind} StoreKind */

/**
 * @typedef {object} ShortcutCardProps
 * @property {string} title
 * @property {string} description
 * @property {string} to
 * @property {string} linkLabel
 * @property {IconName} icon
 * @property {StatAccent} accent
 */

/** @param {ShortcutCardProps} props */
function ShortcutCard({ title, description, to, linkLabel, icon, accent }) {
  return (
    <FeatureCard title={title} icon={icon} accent={accent}>
      <p className="text-sm text-slate-500">{description}</p>
      <Link to={to} className={TILE_LINK_CLASS}>
        {linkLabel} <span className="transition-transform group-hover/link:translate-x-0.5">→</span>
      </Link>
    </FeatureCard>
  )
}

/**
 * Dashboard shortcuts to the Phase 8 order board and sales report.
 * @param {{ kind: StoreKind }} props
 */
export function StoreShortcutCards({ kind }) {
  const config = STORE_KIND_CONFIG[kind]
  return (
    <>
      <ShortcutCard
        title="Orders"
        icon="receipt"
        accent="sky"
        description={
          kind === 'VENDOR'
            ? 'Accept new orders in time, pack them and mark them ready for pickup.'
            : 'Live order board: accept with a preparation time, then preparing and ready.'
        }
        to={config.ordersPath}
        linkLabel="Open the order board"
      />
      <ShortcutCard
        title="Sales reports"
        icon="chart"
        accent="teal"
        description="Daily sales, acceptance rate and your top items."
        to={config.reportsPath}
        linkLabel="View reports"
      />
      {/* Phase 12. */}
      <ShortcutCard
        title="Reviews"
        icon="star"
        accent="amber"
        description="What customers say about your store and its items. Reply publicly."
        to={kind === 'VENDOR' ? '/vendor/reviews' : '/restaurant/reviews'}
        linkLabel="Read reviews"
      />
      <ShortcutCard
        title="Returns"
        icon="return"
        accent="violet"
        description={
          kind === 'VENDOR'
            ? 'Approve or reject return requests before their deadline; restock what came back sellable.'
            : 'Problems customers reported. Approve a refund or reject before the deadline.'
        }
        to={kind === 'VENDOR' ? '/vendor/returns' : '/restaurant/returns'}
        linkLabel="Open returns"
      />
      <ShortcutCard
        title="Coupons"
        icon="ticket"
        accent="rose"
        description="Discount codes for your store, funded by you."
        to={kind === 'VENDOR' ? '/vendor/coupons' : '/restaurant/coupons'}
        linkLabel="Manage coupons"
      />
      {kind === 'VENDOR' && (
        <ShortcutCard
          title="Purchasing"
          icon="clipboard"
          accent="emerald"
          description="Order stock from your suppliers and record what arrives."
          to="/vendor/purchase-orders"
          linkLabel="Open purchase orders"
        />
      )}
    </>
  )
}
