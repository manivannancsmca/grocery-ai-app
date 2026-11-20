import { Icon } from '../../shared/icons'
import {
  DeliveryScooterIllustration,
  GroceryBagIllustration,
  NoOrdersIllustration,
  RestaurantIllustration,
} from '../../shared/illustrations'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../shared/auth/roles').RoleArea} RoleArea */

/**
 * A scene per stakeholder; admins get their shield in the same round frame.
 * @type {Record<RoleArea, ReactNode>}
 */
const ART = {
  CUSTOMER: <GroceryBagIllustration />,
  VENDOR: <NoOrdersIllustration />,
  RESTAURANT_OWNER: <RestaurantIllustration />,
  DELIVERY_PARTNER: <DeliveryScooterIllustration />,
  ADMIN: (
    // Inset like the other scenes, whose circles leave room for their sparkles.
    <div className="p-[12%]">
      <div className="flex aspect-square items-center justify-center rounded-full bg-emerald-50 ring-4 ring-white/10">
        <Icon name="shield" className="size-1/2" />
      </div>
    </div>
  ),
}

/**
 * The chosen stakeholder's illustration in the brand panel's glass card (large screens only).
 * @param {{ area: RoleArea }} props
 */
export function StakeholderArt({ area }) {
  return (
    <div key={area} className="animate-fade-up w-32 shrink-0 xl:w-40">
      <div className="animate-float">{ART[area]}</div>
    </div>
  )
}
