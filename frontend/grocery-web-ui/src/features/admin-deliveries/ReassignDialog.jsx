import { useId, useState } from 'react'
import { ApiError, errorMessage, hasErrorCode, problemString } from '../../shared/api/problemDetail'
import { distanceKm, isGeoPoint } from '../../shared/components/map/types'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Dialog, ErrorState, Spinner, TextareaField } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { formatDistanceKm, formatTime } from '../../shared/utils/format'
import { VEHICLE_TYPES } from '../delivery/api'
import { VEHICLE_LABELS } from '../delivery/schema'
import { DELIVERY_LIMITS, isDeliveryStatus } from '../deliveries/api'
import { deliveryStatusLabel } from '../deliveries/labels'
import { ADMIN_DELIVERY_ERRORS } from './api'
import { useOnlinePartners, useReassignDelivery } from './hooks'

/** @typedef {import('./api').AdminDeliveryView} AdminDeliveryView */
/** @typedef {import('./api').OnlinePartner} OnlinePartner */

/** The radio value of "drop the current partner and search again". */
const SEARCH_AGAIN = '__search_again__'

/**
 * @param {string | null} vehicle
 * @returns {string | null}
 */
function vehicleText(vehicle) {
  if (!vehicle) return null
  return VEHICLE_TYPES.includes(vehicle) ? VEHICLE_LABELS[vehicle] : vehicle
}

/**
 * @typedef {object} Candidate
 * @property {OnlinePartner} partner
 * @property {number | null} km Straight line to the pickup point, when both positions are known.
 * @property {string | null} disabledReason
 */

/**
 * Free partners nearest to the store first; busy ones and the current partner last (not selectable).
 * @param {readonly OnlinePartner[]} partners
 * @param {AdminDeliveryView} delivery
 * @returns {Candidate[]}
 */
function candidates(partners, delivery) {
  const pickup = delivery.pickup && isGeoPoint(delivery.pickup) ? delivery.pickup : null
  return partners
    .map((partner) => ({
      partner,
      km: pickup && isGeoPoint(partner) ? distanceKm(partner, pickup) : null,
      disabledReason:
        partner.partnerId === delivery.partnerId
          ? 'Current partner'
          : partner.busy
            ? 'Busy with another delivery'
            : null,
    }))
    .sort((a, b) => {
      if ((a.disabledReason === null) !== (b.disabledReason === null)) return a.disabledReason === null ? -1 : 1
      return (a.km ?? Number.POSITIVE_INFINITY) - (b.km ?? Number.POSITIVE_INFINITY)
    })
}

/**
 * @typedef {object} ReassignDialogProps
 * @property {AdminDeliveryView | null} delivery The delivery to reassign; null closes the dialog.
 * @property {() => void} onClose
 */

/**
 * Reassign a SEARCHING or ASSIGNED delivery (SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER): pick an online, free
 * partner (nearest to the store first) or "Search again", with an optional reason.
 * @param {ReassignDialogProps} props
 */
export function ReassignDialog({ delivery, onClose }) {
  return (
    <Dialog
      open={delivery !== null}
      onClose={onClose}
      title={delivery ? `Reassign order ${delivery.orderNumber}` : 'Reassign delivery'}
      description={
        delivery?.partnerName
          ? `${delivery.partnerName} is told the delivery was reassigned.`
          : 'Nobody has this delivery yet: assign a partner now, or start a fresh search.'
      }
    >
      {delivery && <ReassignForm delivery={delivery} onClose={onClose} />}
    </Dialog>
  )
}

/** @param {{ delivery: AdminDeliveryView, onClose: () => void }} props */
function ReassignForm({ delivery, onClose }) {
  const toast = useToast()
  const partners = useOnlinePartners(true)
  const reassign = useReassignDelivery()
  const [choice, setChoice] = useState('')
  const [reason, setReason] = useState('')
  const [choiceError, setChoiceError] = useState(null)
  const [formError, setFormError] = useState(null)
  const legendId = useId()
  const list = partners.data ? candidates(partners.data, delivery) : []

  async function submit(event) {
    event.preventDefault()
    setFormError(null)
    if (!choice) {
      setChoiceError('Choose a partner, or “Search again”.')
      return
    }
    const note = reason.trim()
    if (note.length > DELIVERY_LIMITS.reassignReason) return
    const partnerId = choice === SEARCH_AGAIN ? undefined : choice
    const chosen = list.find((entry) => entry.partner.partnerId === partnerId)?.partner
    try {
      await reassign.mutateAsync({
        id: delivery.deliveryId,
        body: { ...(partnerId ? { partnerId } : {}), ...(note ? { reason: note } : {}) },
      })
      toast.success(
        partnerId
          ? `Order ${delivery.orderNumber} assigned to ${chosen?.name ?? 'the partner'}`
          : `Searching for a new partner for order ${delivery.orderNumber}`,
        { description: partnerId ? 'They see it in their app now.' : 'Offers go to the nearest available partners.' },
      )
      onClose()
    } catch (error) {
      if (hasErrorCode(error, ADMIN_DELIVERY_ERRORS.partnerUnavailable)) {
        setChoice('')
        setFormError(
          `${chosen?.name ?? 'That partner'} is no longer available (offline, busy or not approved). The list has been refreshed — choose another partner.`,
        )
        return
      }
      if (hasErrorCode(error, ADMIN_DELIVERY_ERRORS.invalidTransition)) {
        const status = problemString(error, 'currentStatus')
        onClose()
        toast.warning(`Order ${delivery.orderNumber} can no longer be reassigned`, {
          description: `${isDeliveryStatus(status) ? `It is now ${deliveryStatusLabel(status).toLowerCase()}. ` : ''}The monitor has been refreshed.`,
        })
        return
      }
      setFormError(
        `Could not reassign: ${errorMessage(error)}${error instanceof ApiError && error.correlationId ? ` (Ref: ${error.correlationId})` : ''}`,
      )
    }
  }

  const busy = reassign.isPending

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      <fieldset
        aria-labelledby={legendId}
        aria-describedby={choiceError ? `${legendId}-error` : undefined}
        className="space-y-2"
      >
        <legend id={legendId} className="text-sm font-medium text-slate-700">
          Assign to
          <span className="ml-0.5 text-red-600" aria-hidden="true">
            *
          </span>
        </legend>
        <label className="flex cursor-pointer items-start gap-3 rounded-lg border border-slate-200 p-3 has-checked:border-emerald-500 has-checked:bg-emerald-50">
          <input
            type="radio"
            name="partner"
            value={SEARCH_AGAIN}
            checked={choice === SEARCH_AGAIN}
            onChange={() => {
              setChoice(SEARCH_AGAIN)
              setChoiceError(null)
            }}
            className="mt-1 accent-emerald-600"
          />
          <span className="text-sm">
            <span className="block font-medium text-slate-900">Search again</span>
            <span className="block text-slate-500">
              {delivery.partnerName ? `Drop ${delivery.partnerName} and offer` : 'Offer'} the delivery to the nearest
              available partners
              {delivery.partnerName ? ` (not ${delivery.partnerName})` : ''}.
            </span>
          </span>
        </label>

        {partners.isPending ? (
          <Spinner size="sm" label="Loading online partners" />
        ) : partners.isError ? (
          <ErrorState
            title="Could not load online partners"
            message={partners.error.message}
            correlationId={partners.error instanceof ApiError ? partners.error.correlationId : null}
            onRetry={() => void partners.refetch()}
          />
        ) : list.length === 0 ? (
          <p className="rounded-lg bg-slate-50 px-3 py-2 text-sm text-slate-500">No partner is online right now.</p>
        ) : (
          <ul aria-label="Online partners" className="space-y-2">
            {list.map(({ partner, km, disabledReason }) => {
              const details = [
                vehicleText(partner.vehicleType),
                km !== null ? `${formatDistanceKm(km)} from the store` : null,
                partner.locationAt ? `seen ${formatTime(partner.locationAt)}` : null,
              ].filter(Boolean)
              return (
                <li key={partner.partnerId}>
                  <label
                    className={cn(
                      'flex items-start gap-3 rounded-lg border border-slate-200 p-3 has-checked:border-emerald-500 has-checked:bg-emerald-50',
                      disabledReason ? 'cursor-not-allowed opacity-60' : 'cursor-pointer',
                    )}
                  >
                    <input
                      type="radio"
                      name="partner"
                      value={partner.partnerId}
                      checked={choice === partner.partnerId}
                      disabled={disabledReason !== null}
                      onChange={() => {
                        setChoice(partner.partnerId)
                        setChoiceError(null)
                      }}
                      className="mt-1 accent-emerald-600"
                    />
                    <span className="text-sm">
                      <span className="block font-medium text-slate-900">
                        {partner.name ?? partner.partnerId}
                        {disabledReason && (
                          <span className="ml-2 text-xs font-normal text-slate-500">· {disabledReason}</span>
                        )}
                      </span>
                      {details.length > 0 && <span className="block text-slate-500">{details.join(' · ')}</span>}
                    </span>
                  </label>
                </li>
              )
            })}
          </ul>
        )}
        {choiceError && (
          <p id={`${legendId}-error`} className="text-xs font-medium text-red-600">
            {choiceError}
          </p>
        )}
      </fieldset>

      <TextareaField
        label={
          <>
            Reason <span className="font-normal text-slate-500">(optional)</span>
          </>
        }
        rows={2}
        maxLength={DELIVERY_LIMITS.reassignReason}
        value={reason}
        onChange={(event) => setReason(event.target.value)}
        placeholder="e.g. Partner's scooter broke down"
      />

      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={busy}>
          Cancel
        </Button>
        <Button type="submit" disabled={busy}>
          {busy && <Spinner size="sm" className="[&_svg]:text-white" />}
          Reassign
        </Button>
      </div>
    </form>
  )
}
