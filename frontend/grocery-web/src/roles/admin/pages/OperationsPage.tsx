import { useStuckOrders } from '../../../features/admin-orders/hooks'
import { isConnectorHealthy } from '../../../features/operations/api'
import { ConnectorsPanel } from '../../../features/operations/ConnectorsPanel'
import { DeadLettersPanel } from '../../../features/operations/DeadLettersPanel'
import { useConnectors } from '../../../features/operations/hooks'
import { StuckOrdersPanel } from '../../../features/operations/StuckOrdersPanel'
import { Badge, PageHeader, type TabItem, Tabs } from '../../../shared/components/ui'
import { useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'

type OperationsTab = 'dead-letters' | 'connectors' | 'stuck-orders'

function isOperationsTab(value: string | null): value is OperationsTab {
  return value === 'dead-letters' || value === 'connectors' || value === 'stuck-orders'
}

/** Filters of the dead-letter tab, dropped when leaving it. */
const DEAD_LETTER_PARAMS = { service: null, status: null, eventType: null, group: null, page: null } as const

/**
 * `/admin/operations` (Phase 10 §8) — the event backbone for operators: dead letters of every service (replay,
 * discard, replay all), Debezium connector health (restart) and stuck orders. Every admin may look; the write
 * actions are for SUPER_ADMIN and OPS_ADMIN. `?tab=` in the URL.
 */
export function OperationsPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const tabParam = searchParams.get('tab')
  const tab: OperationsTab = isOperationsTab(tabParam) ? tabParam : 'dead-letters'
  // Both are cheap and shared with their tabs: the tab labels carry their counts.
  const stuck = useStuckOrders()
  const connectors = useConnectors()
  const stuckCount = stuck.data?.length ?? 0
  const connectorProblems = connectors.data?.filter((connector) => !isConnectorHealthy(connector)).length ?? 0

  const tabs: readonly TabItem<OperationsTab>[] = [
    // The pictures are decorative: a tab's name stays its text (and count).
    {
      id: 'dead-letters',
      label: (
        <>
          <Icon name="mail" className="size-4" />
          Dead letters
        </>
      ),
    },
    {
      id: 'connectors',
      label: (
        <>
          <Icon name="swap" className="size-4" />
          Connectors{' '}
          {connectorProblems > 0 && <Badge tone="red">{connectorProblems}</Badge>}
        </>
      ),
    },
    {
      id: 'stuck-orders',
      label: (
        <>
          <Icon name="hourglass" className="size-4" />
          Stuck orders{' '}
          {stuckCount > 0 && <Badge tone="amber">{stuckCount}</Badge>}
        </>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        icon="settings"
        title="Operations"
        description="Events that could not be processed, the Debezium connectors that publish them, and orders that stopped moving."
      />
      <Tabs
        label="Operations sections"
        tabs={tabs}
        value={tab}
        onChange={(next) => updateParams({ ...DEAD_LETTER_PARAMS, tab: next === 'dead-letters' ? null : next }, { replace: true })}
      >
        {tab === 'dead-letters' && <DeadLettersPanel />}
        {tab === 'connectors' && <ConnectorsPanel />}
        {tab === 'stuck-orders' && <StuckOrdersPanel />}
      </Tabs>
    </div>
  )
}
