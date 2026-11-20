import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { categoryOf, SEVERITY_ICON_CLASSES, severityOf } from './style'

const CATEGORY_ICONS: Record<string, IconName> = {
  ORDER: 'bag',
  PAYMENT: 'card',
  STOCK: 'box',
  PRICING: 'tag',
  ACCOUNT: 'user',
  APPROVAL: 'shield',
  DELIVERY: 'scooter',
  REVIEW: 'star',
}

/** Order, delivery, watchdog, assistant, review and return steps get their own pictures; every other type uses its category's. */
const TYPE_ICONS: Record<string, IconName> = {
  // Phase 8: a new order for the store, accepted by it, ready for pickup, or not accepted in time.
  NEW_ORDER: 'receipt',
  ORDER_ACCEPTED: 'bagCheck',
  ORDER_READY: 'cloche',
  ORDER_AUTO_REJECTED: 'clock',
  // Phase 9: offers, assignment and the way to the customer ride a scooter…
  DELIVERY_OFFER: 'scooter',
  PARTNER_ASSIGNED: 'scooter',
  OUT_FOR_DELIVERY: 'scooter',
  // …the order waiting at the store is under its serving dome…
  PICKUP_READY: 'cloche',
  // …and the end of the trip is a house: ticked when delivered, crossed when not.
  ORDER_DELIVERED: 'homeCheck',
  DELIVERY_FAILED: 'homeCross',
  DELIVERY_FAILED_ADMIN: 'homeCross',
  NO_DELIVERY_PARTNER: 'pinQuestion',
  DELIVERY_REASSIGNED: 'swap',
  DELIVERY_CANCELLED: 'xCircle',
  // Phase 10: the watchdog's stuck order waits in its hourglass.
  STUCK_ORDER: 'hourglass',
  // Phase 11: a proposal waits for a decision (question), was not approved or expired (paused), ran (tick) or failed (cross).
  AI_APPROVAL_REQUESTED: 'sparkleQuestion',
  AI_APPROVAL_DECIDED: 'sparklePause',
  AI_ACTION_COMPLETED: 'sparkleCheck',
  AI_ACTION_FAILED: 'sparkleCross',
  // Phase 12: a review is a star (a new one with a plus, one to moderate with a flag, one not published crossed out);
  // a reply is a speech bubble; a return is a U-turn arrow (escalated "!", approved a tick, rejected a cross).
  NEW_REVIEW: 'starPlus',
  REVIEW_FLAGGED: 'starFlag',
  REVIEW_NOT_PUBLISHED: 'starCross',
  REVIEW_REPLY: 'chat',
  RETURN_REQUESTED: 'return',
  RETURN_ESCALATED: 'returnAlert',
  RETURN_APPROVED: 'returnCheck',
  RETURN_REJECTED: 'returnCross',
}

interface NotificationIconProps {
  category: string
  severity: string
  /** The notification type; many types have their own icon. */
  type?: string
  className?: string
}

/** Type or category icon in a circle tinted by severity (decorative: the text says the same). A bell when unknown. */
export function NotificationIcon({ category, severity, type, className }: NotificationIconProps) {
  const known = categoryOf(category)
  const name = (type ? TYPE_ICONS[type] : undefined) ?? (known ? CATEGORY_ICONS[known] : undefined) ?? 'bell'
  return (
    <span
      aria-hidden="true"
      className={cn(
        'flex size-9 shrink-0 items-center justify-center rounded-full ring-1 ring-inset',
        SEVERITY_ICON_CLASSES[severityOf(severity)],
        className,
      )}
    >
      <Icon name={name} className="size-5" />
    </span>
  )
}
