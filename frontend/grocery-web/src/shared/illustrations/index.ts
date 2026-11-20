/**
 * Original flat vector illustrations (inline SVG, brand palette) for customer screens: heroes, empty states and
 * error pages. Decorative by default (`aria-hidden`); pass `title` when one carries meaning. Size them with a
 * width class (`className="w-48"`); the height follows the aspect ratio.
 */
export type { IllustrationProps } from './Illustration'
export {
  AssistantIllustration,
  EmailIllustration,
  MapPinIllustration,
  NoOrdersIllustration,
  NotificationsIllustration,
  ProfileIllustration,
  ReturnsIllustration,
  ReviewsIllustration,
} from './account'
export {
  DeliveryScooterIllustration,
  EmptyCartIllustration,
  GroceryBagIllustration,
  OrderSuccessIllustration,
  RestaurantIllustration,
  SearchIllustration,
} from './shopping'
export { EmptyBoxIllustration, ErrorIllustration, LostIllustration } from './status'
