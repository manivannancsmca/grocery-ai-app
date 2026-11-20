import { Badge, Button } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatAddress } from '../../shared/utils/format'

/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').Address} Address */

/**
 * @typedef {object} AddressCardProps
 * @property {Address} address
 * @property {boolean} [busy]
 * @property {() => void} onEdit
 * @property {() => void} onDelete
 * @property {() => void} onMakeDefault
 */

/**
 * A house for "Home", a briefcase for "Work" / "Office", a pin for anything else.
 * @param {string} label
 * @returns {IconName}
 */
function labelIcon(label) {
  if (/\b(home|house|flat|apartment)\b/i.test(label)) return 'home'
  if (/\b(work|office)\b/i.test(label)) return 'briefcase'
  return 'mapPin'
}

/** @param {AddressCardProps} props */
export function AddressCard({ address, busy, onEdit, onDelete, onMakeDefault }) {
  const pinned = address.latitude !== null && address.longitude !== null
  return (
    <article
      aria-label={`${address.label} address`}
      className={cn(
        'group flex flex-col rounded-2xl border bg-white p-5 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md',
        address.isDefault ? 'border-emerald-300 ring-1 ring-emerald-100' : 'border-slate-200/80',
      )}
    >
      <div className="flex items-start gap-3">
        <span
          aria-hidden="true"
          className={cn(
            'flex size-11 shrink-0 items-center justify-center rounded-xl',
            address.isDefault
              ? 'bg-emerald-600 text-white shadow-sm shadow-emerald-600/25'
              : 'bg-emerald-50 text-emerald-700',
          )}
        >
          <Icon name={labelIcon(address.label)} className="size-5" />
        </span>
        <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2 gap-y-1 pt-2">
          <h3 className="min-w-0 truncate text-base font-semibold text-slate-900">{address.label}</h3>
          {address.isDefault && (
            <Badge tone="green" className="gap-1 font-semibold">
              <Icon name="check" className="size-3" />
              Default
            </Badge>
          )}
        </div>
      </div>

      <div className="mt-4 flex-1 space-y-2 text-sm">
        <p className="leading-relaxed text-slate-600">{formatAddress(address)}</p>
        <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-slate-700">
          <span className="inline-flex items-center gap-1.5 font-medium">
            <Icon name="user" className="size-4" />
            {address.recipientName}
          </span>
          <span className="inline-flex items-center gap-1.5 tabular-nums">
            <Icon name="phone" className="size-4" />
            {address.phone}
          </span>
        </p>
        {pinned && (
          <p
            className="inline-flex items-center gap-1 rounded-full bg-sky-50 px-2 py-0.5 text-xs font-medium text-sky-700"
            title={`${address.latitude}, ${address.longitude}`}
          >
            <Icon name="mapPin" className="size-3.5" />
            Pinned on the map
          </p>
        )}
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-slate-100 pt-3">
        <Button variant="secondary" size="sm" onClick={onEdit} disabled={busy}>
          <Icon name="pencil" className="size-4" />
          Edit
        </Button>
        {!address.isDefault && (
          <Button
            variant="ghost"
            size="sm"
            className="text-emerald-700 hover:bg-emerald-50 hover:text-emerald-800"
            onClick={onMakeDefault}
            disabled={busy}
          >
            <Icon name="checkCircle" className="size-4" />
            Set as default
          </Button>
        )}
        <Button
          variant="ghost"
          size="sm"
          className="ml-auto text-red-600 hover:bg-red-50 hover:text-red-700"
          onClick={onDelete}
          disabled={busy}
        >
          <Icon name="trash" className="size-4" />
          <span className="sr-only sm:not-sr-only">Delete</span>
        </Button>
      </div>
    </article>
  )
}
