import { Link } from 'react-router'
import { ConnectorsPanel } from '../../../features/operations/ConnectorsPanel'
import { SystemStatusGrid } from '../../../features/system/SystemStatusGrid'
import { PageHeader } from '../../../shared/components/ui'
import { Icon } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../../shared/icons').IconName} IconName */

/**
 * @typedef {object} SectionTitleProps
 * @property {string} id
 * @property {IconName} icon
 * @property {string} tile Gradient + ring classes of the icon tile.
 * @property {string} title
 * @property {ReactNode} children
 */

/**
 * A section's heading: a colourful picture, the title (the section's accessible name) and a line of help.
 * @param {SectionTitleProps} props
 */
function SectionTitle({ id, icon, tile, title, children }) {
  return (
    <div className="flex items-start gap-3">
      <span
        aria-hidden="true"
        className={cn(
          'flex size-11 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br shadow-sm ring-1',
          tile,
        )}
      >
        <Icon name={icon} className="size-6" />
      </span>
      <div className="min-w-0">
        <h2 id={id} className="text-lg font-semibold text-slate-900">
          {title}
        </h2>
        <p className="mt-0.5 text-sm text-slate-500">{children}</p>
      </div>
    </div>
  )
}

export function SystemStatusPage() {
  return (
    <div className="space-y-10">
      <div>
        <PageHeader
          icon="pulse"
          title="System status"
          description="Health of each backend service through the API gateway, checked every 15 seconds."
        />
        <section aria-labelledby="backend-services" className="space-y-4">
          <SectionTitle
            id="backend-services"
            icon="pulse"
            tile="from-emerald-50 to-teal-50 ring-emerald-100"
            title="Backend services"
          >
            One tile per service, from its health check through the gateway; red or amber tiles need attention.
          </SectionTitle>
          <SystemStatusGrid />
        </section>
      </div>
      {/* Phase 10 §7: the Debezium connectors that publish every service's outbox to Kafka. */}
      <section aria-labelledby="debezium-connectors" className="space-y-4">
        <SectionTitle
          id="debezium-connectors"
          icon="swap"
          tile="from-violet-50 to-sky-50 ring-violet-100"
          title="Debezium connectors"
        >
          Each service’s outbox reaches Kafka through its connector; a failed task holds events back until it runs
          again. Dead letters and stuck orders are on the{' '}
          <Link
            to="/admin/operations"
            className="inline-flex items-center gap-0.5 font-medium text-emerald-700 underline-offset-2 hover:text-emerald-800 hover:underline"
          >
            Operations
            <Icon name="arrowRight" className="size-3.5" />
          </Link>{' '}
          page.
        </SectionTitle>
        <ConnectorsPanel />
      </section>
    </div>
  )
}
