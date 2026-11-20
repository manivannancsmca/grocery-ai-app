import { useToast } from '../../shared/components/toast/useToast'
import { Switch } from '../../shared/components/ui'
import { menuErrorMessage } from './errors'
import { useSetAcceptingOrders } from './hooks'

interface AcceptingOrdersSwitchProps {
  acceptingOrders: boolean
  disabled?: boolean
  /** Only the switch: "Accepting orders" becomes its accessible name (for a tile that shows the state itself). */
  compact?: boolean
  className?: string
}

/** Owner switch to pause / resume new orders (`PUT /api/restaurant/accepting-orders`). */
export function AcceptingOrdersSwitch({ acceptingOrders, disabled, compact = false, className }: AcceptingOrdersSwitchProps) {
  const toast = useToast()
  const mutation = useSetAcceptingOrders()
  // Show the requested state while the request runs.
  const checked = mutation.isPending ? mutation.variables : acceptingOrders
  const description = checked
    ? 'Customers can order from you. Turn this off to pause new orders for a while.'
    : 'New orders are paused. Customers still see your menu with a "not accepting orders" note.'

  return (
    <Switch
      className={className}
      label="Accepting orders"
      hideLabel={compact}
      description={compact ? undefined : description}
      checked={checked}
      pending={mutation.isPending}
      disabled={disabled}
      onChange={(next) =>
        mutation.mutate(next, {
          onSuccess: (result) =>
            toast.success(result.acceptingOrders ? 'You are accepting orders again.' : 'New orders are paused.'),
          onError: (error) => toast.error(`Could not change it: ${menuErrorMessage(error)}`),
        })
      }
    />
  )
}
