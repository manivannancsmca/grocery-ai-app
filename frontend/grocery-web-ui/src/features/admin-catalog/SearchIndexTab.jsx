import { useState } from 'react'
import { ApiError, errorMessage } from '../../shared/api/problemDetail'
import { useAuth } from '../../shared/auth/useAuth'
import { useToast } from '../../shared/components/toast/useToast'
import {
  Alert,
  Badge,
  Button,
  Card,
  ConfirmDialog,
  DataTable,
  EmptyState,
  ErrorState,
  Spinner,
  StatCard,
} from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { formatCount } from '../../shared/utils/format'
import {
  useRepublishCatalog,
  useRepublishListings,
  useRepublishPrices,
  useRepublishStock,
  useSearchIndexes,
} from './hooks'

/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').SearchIndexStatus} SearchIndexStatus */

/** @type {Record<string, BadgeTone>} */
const HEALTH_TONES = { green: 'green', yellow: 'amber', red: 'red' }

/** @typedef {'catalog' | 'prices' | 'stock' | 'restaurants'} Target */

/** @type {Record<Target, { title: string, body: string, label: string, button: string }>} */
const CONFIRM_TEXT = {
  catalog: {
    title: 'Republish the catalog?',
    body: 'Every category and product snapshot is sent to search again. Use it after the search index was lost or recreated. Customers are not affected.',
    label: 'Republish catalog',
    button: 'Republish catalog',
  },
  prices: {
    title: 'Republish prices?',
    body: 'Every live grocery price is sent to search again. Run it after republishing the catalog. Customers are not affected.',
    label: 'Republish prices',
    button: 'Republish prices',
  },
  stock: {
    title: 'Republish stock?',
    body: 'The in-stock flag of every product is sent to search again. Run it after republishing prices. Customers are not affected.',
    label: 'Republish stock',
    button: 'Republish stock',
  },
  restaurants: {
    title: 'Republish restaurant listings?',
    body: 'Every approved restaurant and its menu is sent to search again. Customers are not affected.',
    label: 'Republish listings',
    button: 'Republish restaurant listings',
  },
}

/** @type {readonly Target[]} */
const TARGETS = ['catalog', 'prices', 'stock', 'restaurants']

/** @type {Record<Target, IconName>} */
const TARGET_ICONS = { catalog: 'box', prices: 'tag', stock: 'basket', restaurants: 'cloche' }

/**
 * Headline numbers of the indexes already loaded (no extra request): how many, their documents and their health.
 * @param {{ indexes: readonly SearchIndexStatus[] }} props
 */
function IndexSummary({ indexes }) {
  const documents = indexes.reduce((sum, index) => sum + index.documents, 0)
  const healthy = indexes.filter((index) => index.health === 'green').length
  const notGreen = indexes.length - healthy
  return (
    <section aria-label="Search index summary">
      <dl className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard label="Indexes" value={formatCount(indexes.length)} icon="list" accent="sky" />
        <StatCard
          label="Documents"
          value={formatCount(documents)}
          icon="search"
          accent="violet"
          hint="Across every index"
        />
        <StatCard
          label="Healthy"
          value={formatCount(healthy)}
          icon="checkCircle"
          accent="emerald"
          valueClassName={healthy > 0 ? 'text-emerald-700' : undefined}
          hint="Green health"
        />
        <StatCard
          label="Yellow or red"
          value={formatCount(notGreen)}
          icon="warning"
          accent={notGreen > 0 ? 'rose' : 'amber'}
          valueClassName={notGreen > 0 ? 'text-red-700' : undefined}
          hint="Worth a look"
        />
      </dl>
    </section>
  )
}

/** Elasticsearch index health and the republish actions (SUPER_ADMIN / OPS_ADMIN). */
export function SearchIndexTab() {
  const { can } = useAuth()
  const toast = useToast()
  const indexes = useSearchIndexes()
  const republishCatalog = useRepublishCatalog()
  const republishPrices = useRepublishPrices()
  const republishStock = useRepublishStock()
  const republishListings = useRepublishListings()
  const [confirm, setConfirm] = useState(null)
  const [confirmError, setConfirmError] = useState(null)
  const canRepublish = can('search.republish')
  const running =
    republishCatalog.isPending || republishPrices.isPending || republishStock.isPending || republishListings.isPending

  function close() {
    setConfirm(null)
    setConfirmError(null)
  }

  function run(target) {
    setConfirmError(null)
    const done = (message) => {
      close()
      toast.success(message)
    }
    const onError = (error) => setConfirmError(`Republishing failed: ${errorMessage(error)}`)
    switch (target) {
      case 'catalog':
        republishCatalog.mutate(undefined, {
          onSuccess: (result) =>
            done(
              `Republished ${formatCount(result.categories)} categories and ${formatCount(result.products)} products. The index catches up within seconds.`,
            ),
          onError,
        })
        break
      case 'prices':
        republishPrices.mutate(undefined, {
          onSuccess: (result) => done(`Republished ${formatCount(result.prices)} prices.`),
          onError,
        })
        break
      case 'stock':
        republishStock.mutate(undefined, {
          onSuccess: (result) => done(`Republished the stock status of ${formatCount(result.products)} products.`),
          onError,
        })
        break
      case 'restaurants':
        republishListings.mutate(undefined, {
          onSuccess: (result) => done(`Republished ${formatCount(result.restaurants)} restaurant listings.`),
          onError,
        })
        break
    }
  }

  const columns = [
    {
      key: 'alias',
      header: 'Alias',
      sortValue: (index) => index.alias,
      cell: (index) => (
        <div className="flex items-center gap-2.5">
          <span
            aria-hidden="true"
            className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-violet-50 to-fuchsia-50 ring-1 ring-violet-100"
          >
            <Icon name="search" className="size-4" />
          </span>
          <span className="font-medium text-slate-900">{index.alias}</span>
        </div>
      ),
    },
    {
      key: 'index',
      header: 'Index',
      sortValue: (index) => index.index,
      cell: (index) => <span className="font-mono text-xs">{index.index}</span>,
    },
    {
      key: 'documents',
      header: 'Documents',
      className: 'text-right',
      sortValue: (index) => index.documents,
      cell: (index) => (
        <span className="font-semibold text-slate-900 tabular-nums">{index.documents.toLocaleString('en-IN')}</span>
      ),
    },
    {
      key: 'health',
      header: 'Health',
      sortValue: (index) => index.health,
      cell: (index) => (
        <Badge tone={HEALTH_TONES[index.health] ?? 'gray'} dot className="capitalize">
          {index.health}
        </Badge>
      ),
    },
  ]

  return (
    <div className="space-y-4">
      <p className="text-sm text-slate-500">
        Search runs on Elasticsearch, fed by catalog, price, stock and restaurant events. Refreshed every 30 seconds.
      </p>

      {indexes.data && indexes.data.length > 0 && <IndexSummary indexes={indexes.data} />}

      {canRepublish && (
        <Card
          icon="refresh"
          title="Republish to search"
          description="Send data to the search index again, e.g. after an index was lost or recreated. Customers are not affected."
        >
          <div className="flex flex-wrap gap-2">
            {TARGETS.map((target) => (
              <Button key={target} variant="secondary" onClick={() => setConfirm(target)}>
                <Icon name={TARGET_ICONS[target]} className="size-4" />
                {CONFIRM_TEXT[target].button}
              </Button>
            ))}
          </div>
          <div className="mt-4">
            <Alert tone="info" title="After the products index was rebuilt">
              Republish in this order: <strong>catalog → prices → stock</strong>. Each product document has separately
              versioned catalog, price and stock parts, so repeating a step is harmless.
            </Alert>
          </div>
        </Card>
      )}

      {indexes.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner size="lg" label="Loading search indexes" />
        </div>
      ) : indexes.isError ? (
        <ErrorState
          title="Could not load the search indexes"
          message={indexes.error.message}
          correlationId={indexes.error instanceof ApiError ? indexes.error.correlationId : null}
          onRetry={() => void indexes.refetch()}
        />
      ) : (
        <DataTable
          caption="Search indexes"
          columns={columns}
          rows={indexes.data}
          getRowKey={(index) => index.alias}
          fetching={indexes.isFetching}
          empty={
            <EmptyState title="No indexes reported" description="The search service has not created its indexes yet." />
          }
        />
      )}

      <ConfirmDialog
        open={confirm !== null}
        title={confirm ? CONFIRM_TEXT[confirm].title : ''}
        confirmLabel={confirm ? CONFIRM_TEXT[confirm].label : 'Republish'}
        pending={running}
        error={confirmError}
        onClose={close}
        onConfirm={() => confirm && run(confirm)}
      >
        <p>{confirm ? CONFIRM_TEXT[confirm].body : null}</p>
      </ConfirmDialog>
    </div>
  )
}
