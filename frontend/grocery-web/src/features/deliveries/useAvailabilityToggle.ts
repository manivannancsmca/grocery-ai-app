import { useToast } from '../../shared/components/toast/useToast'
import { availabilityError } from './errors'
import { useSetAvailability } from './hooks'
import type { LocationSharing } from './useLocationReporter'

/**
 * Online ⇄ offline (`PUT …/availability`) for every control on the partner's dashboard (the switch in the banner and
 * the big "Go online" button): one mutation, so they show the same busy state. Going online resumes location
 * sharing; 403 PARTNER_NOT_ACTIVE and 409 ACTIVE_DELIVERY are explained in a toast.
 */
export function useAvailabilityToggle(online: boolean, sharing: Pick<LocationSharing, 'resume'>) {
  const toast = useToast()
  const availability = useSetAvailability()

  async function setOnline(next: boolean) {
    try {
      await availability.mutateAsync(next)
      if (next) {
        sharing.resume()
        toast.success('You are online', { description: 'Delivery offers near you appear on this page.' })
      } else {
        toast.info('You are offline', { description: 'You will not get new delivery offers.' })
      }
    } catch (error) {
      const failure = availabilityError(error)
      toast.show(failure.tone, failure.message, { description: failure.description })
    }
  }

  return {
    pending: availability.isPending,
    toggle: () => void setOnline(!online),
    goOnline: () => void setOnline(true),
  }
}

export type AvailabilityToggle = ReturnType<typeof useAvailabilityToggle>
