import { useState } from 'react'
import { Link } from 'react-router'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Badge, Button, ConfirmDialog, Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import {
  isProductStatus,
  PRODUCT_STATUS_LABELS,
  PRODUCT_STATUS_TONES,
  type ProductStatusAction,
  type ProductView,
  VENDOR_NOT_ACTIVE,
} from './api'
import { useProductStatusAction } from './hooks'

export function ProductStatusBadge({ status }: { status: string }) {
  if (!isProductStatus(status)) return <Badge>{status}</Badge>
  return (
    <Badge tone={PRODUCT_STATUS_TONES[status]} dot>
      {PRODUCT_STATUS_LABELS[status]}
    </Badge>
  )
}

/**
 * 403 VENDOR_NOT_ACTIVE: products, stock and prices can be managed only while the vendor is approved.
 * `area` names the page ("Products", "Inventory", "Pricing").
 */
export function VendorNotActiveNotice({ area = 'Products', task = 'add and manage products' }: { area?: string; task?: string }) {
  return (
    <Alert
      tone="warning"
      role="status"
      title={`${area} open${area.endsWith('s') ? '' : 's'} up once your business is approved`}
      actions={
        <Link to="/vendor/profile" className="text-sm font-semibold text-amber-900 underline hover:text-amber-950">
          View business profile
        </Link>
      }
    >
      You can {task} as soon as our team approves your business. If it was approved just now, reload this page in a moment.
    </Alert>
  )
}

const SUCCESS: Record<ProductStatusAction, (name: string) => string> = {
  publish: (name) => `${name} is published. It shows up in search within a few seconds.`,
  unpublish: (name) => `${name} is unpublished and hidden from customers.`,
  archive: (name) => `${name} was archived.`,
}

function failureMessage(error: unknown, action: ProductStatusAction): string {
  if (hasErrorCode(error, 'CATEGORY_INACTIVE')) {
    return 'Its category is inactive, so it cannot be published. Edit the product and choose an active category.'
  }
  if (hasErrorCode(error, 'INVALID_STATUS_TRANSITION')) return 'Its status changed in the meantime. The list has been refreshed.'
  if (hasErrorCode(error, 'PRODUCT_ARCHIVED')) return 'This product is archived and cannot be changed.'
  if (hasErrorCode(error, VENDOR_NOT_ACTIVE)) return 'Your business is not active, so products cannot be changed right now.'
  return `Could not ${action} the product: ${errorMessage(error)}`
}

interface ProductStatusActionsProps {
  product: ProductView
  /** `row`: compact buttons named after the product (tables); `header`: the product page. */
  variant?: 'row' | 'header'
  onArchived?: () => void
}

/** Publish / unpublish / archive (with confirmation) for one product, with toasts for the outcome. */
export function ProductStatusActions({ product, variant = 'row', onArchived }: ProductStatusActionsProps) {
  const toast = useToast()
  const mutation = useProductStatusAction()
  const [confirmArchive, setConfirmArchive] = useState(false)
  const [archiveError, setArchiveError] = useState<string | null>(null)
  const busyAction = mutation.isPending && mutation.variables.product.id === product.id ? mutation.variables.action : null
  const label = (verb: string) => (variant === 'row' ? `${verb} ${product.name}` : undefined)

  function run(action: 'publish' | 'unpublish') {
    mutation.mutate(
      { action, product },
      {
        onSuccess: () => toast.success(SUCCESS[action](product.name)),
        onError: (error) => toast.error(failureMessage(error, action)),
      },
    )
  }

  if (product.status === 'ARCHIVED') return null

  return (
    <div className={cn('flex items-center gap-2', variant === 'row' ? 'shrink-0 flex-nowrap justify-end' : 'flex-wrap')}>
      {(product.status === 'DRAFT' || product.status === 'UNPUBLISHED') && (
        <Button
          size="sm"
          variant={variant === 'header' ? 'primary' : 'secondary'}
          className={variant === 'row' ? 'text-emerald-700' : undefined}
          aria-label={label('Publish')}
          disabled={busyAction !== null}
          onClick={() => run('publish')}
        >
          {busyAction === 'publish' ? (
            <Spinner size="sm" className={variant === 'header' ? '[&_svg]:text-white' : undefined} />
          ) : (
            <Icon name="eye" className="size-4" />
          )}
          Publish
        </Button>
      )}
      {product.status === 'PUBLISHED' && (
        <Button size="sm" variant="secondary" aria-label={label('Unpublish')} disabled={busyAction !== null} onClick={() => run('unpublish')}>
          {busyAction === 'unpublish' ? <Spinner size="sm" /> : <Icon name="eyeOff" className="size-4" />}
          Unpublish
        </Button>
      )}
      {/* In a list row the archive button is just its icon (named "Archive <product>"); on the product page it has text. */}
      <Button
        size="sm"
        variant="ghost"
        className={cn('text-red-600 hover:bg-red-50 hover:text-red-700', variant === 'row' && 'w-8 px-0')}
        aria-label={label('Archive')}
        title={variant === 'row' ? 'Archive' : undefined}
        disabled={busyAction !== null}
        onClick={() => {
          setArchiveError(null)
          setConfirmArchive(true)
        }}
      >
        <Icon name="box" className="size-4" />
        {variant === 'header' && 'Archive'}
      </Button>

      <ConfirmDialog
        open={confirmArchive}
        title={`Archive ${product.name}?`}
        confirmLabel="Archive product"
        tone="danger"
        pending={busyAction === 'archive'}
        error={archiveError}
        onClose={() => setConfirmArchive(false)}
        onConfirm={() => {
          setArchiveError(null)
          mutation.mutate(
            { action: 'archive', product },
            {
              onSuccess: () => {
                setConfirmArchive(false)
                toast.success(SUCCESS.archive(product.name))
                onArchived?.()
              },
              onError: (error) => setArchiveError(failureMessage(error, 'archive')),
            },
          )
        }}
      >
        <p>
          It disappears from search and from your active lists. Archived products are kept for order history and cannot be
          edited or published again.
        </p>
      </ConfirmDialog>
    </div>
  )
}
