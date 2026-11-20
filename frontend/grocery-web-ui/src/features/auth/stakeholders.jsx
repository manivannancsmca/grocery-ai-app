import { ROLE_AREAS } from '../../shared/auth/roles'

/** @typedef {import('../../shared/auth/roles').RoleArea} RoleArea */
/** @typedef {import('../../shared/auth/roles').SelfRegistrationRole} SelfRegistrationRole */
/** @typedef {import('../../shared/icons').IconName} IconName */

/**
 * What the sign-in screen says to one kind of FreshCart user. One entry per UI area.
 * @typedef {object} Stakeholder
 * @property {RoleArea} id
 * @property {string} label Short tab label.
 * @property {string} headline
 * @property {string} summary
 * @property {[ string, string, string ]} points What this person does after signing in.
 * @property {SelfRegistrationRole | null} registerRole Role to preselect on the registration page; null when accounts are not self-service.
 * @property {string} accountName "Create a … account" wording.
 * @property {string} joinSummary One line for the registration screen. Empty when accounts are not self-service.
 * @property {readonly string[]} joinSteps What registering as this stakeholder involves, in order. Empty when accounts are not self-service.
 * @property {IconName} icon
 */

/** @type {Record<RoleArea, Stakeholder>} */
export const STAKEHOLDERS = {
  CUSTOMER: {
    id: 'CUSTOMER',
    label: 'Customer',
    headline: 'Groceries and restaurant meals, delivered to your door.',
    summary: 'Pick up where you left off: your cart, addresses and orders are waiting.',
    points: [
      'Shop groceries and order from local restaurants',
      'Follow every order live, from the store to your doorstep',
      'Ask the AI assistant for help while you shop',
    ],
    registerRole: 'CUSTOMER',
    accountName: 'customer',
    joinSummary: 'Create an account and your first order is a few minutes away.',
    joinSteps: ['Sign up with your email', 'Add a delivery address', 'Start ordering straight away'],
    icon: 'cart',
  },
  VENDOR: {
    id: 'VENDOR',
    label: 'Vendor',
    headline: 'Run your grocery store from one dashboard.',
    summary: 'Everything your store needs for the day, in one place.',
    points: [
      'Manage products, stock levels and prices',
      'Accept and fulfil incoming orders',
      'Keep an eye on sales reports, reviews and coupons',
    ],
    registerRole: 'VENDOR',
    accountName: 'vendor',
    joinSummary: 'Put your store on FreshCart and start taking orders online.',
    joinSteps: ['Sign up with your email', 'Fill in your store profile', 'Submit it for approval, then start selling'],
    icon: 'store',
  },
  RESTAURANT_OWNER: {
    id: 'RESTAURANT_OWNER',
    label: 'Restaurant',
    headline: 'Keep your menu and your kitchen in sync.',
    summary: 'Take orders as they come in and keep customers posted.',
    points: [
      'Update your menu and switch ordering on or off',
      'Accept orders and move them through the kitchen',
      'See reports, reviews and coupons for your restaurant',
    ],
    registerRole: 'RESTAURANT_OWNER',
    accountName: 'restaurant owner',
    joinSummary: 'Put your restaurant and its menu on FreshCart.',
    joinSteps: [
      'Sign up with your email',
      'Fill in your restaurant profile',
      'Submit it for approval, then take orders',
    ],
    icon: 'cutlery',
  },
  DELIVERY_PARTNER: {
    id: 'DELIVERY_PARTNER',
    label: 'Delivery',
    headline: 'Deliver orders and earn on your own schedule.',
    summary: 'Go online when you are ready and take the offers that suit you.',
    points: [
      'Go online and accept delivery offers',
      'Share your live location while you are on a delivery',
      'Look back at every delivery you have completed',
    ],
    registerRole: 'DELIVERY_PARTNER',
    accountName: 'delivery partner',
    joinSummary: 'Sign up, get approved and start taking deliveries.',
    joinSteps: [
      'Sign up with your email',
      'Add your vehicle and licence details',
      'Submit your profile for approval, then go online',
    ],
    icon: 'scooter',
  },
  ADMIN: {
    id: 'ADMIN',
    label: 'Admin',
    headline: 'Keep the whole marketplace running smoothly.',
    summary: 'For the FreshCart operations, finance, support and delivery teams.',
    points: [
      'Approve vendors, restaurants and delivery partners',
      'Oversee orders, payments and deliveries',
      'Reports, AI audit and system status in one console',
    ],
    registerRole: null,
    accountName: 'admin',
    joinSummary: '',
    joinSteps: [],
    icon: 'shield',
  },
}

/** @type {readonly Stakeholder[]} */
export const STAKEHOLDER_LIST = ROLE_AREAS.map((area) => STAKEHOLDERS[area])

/**
 * Dashboard path prefix → the area it belongs to. `/restaurants` (plural) is the public customer listing.
 * @type {ReadonlyArray<[ prefix: string, area: RoleArea ]>}
 */
const AREA_PATHS = [
  ['/vendor', 'VENDOR'],
  ['/restaurant', 'RESTAURANT_OWNER'],
  ['/delivery', 'DELIVERY_PARTNER'],
  ['/admin', 'ADMIN'],
]

/**
 * Which stakeholder the sign-in screen opens on: `?role=` wins (links such as `/login?role=VENDOR`),
 * then the page the visitor was sent here from (e.g. an expired session on `/delivery/profile`),
 * otherwise the customer view.
 * @param {string | null} roleParam
 * @param {string | null} from
 * @returns {RoleArea}
 */
export function initialStakeholder(roleParam, from) {
  const requested = roleParam?.toUpperCase()
  if (requested && ROLE_AREAS.includes(requested)) return requested
  if (from) {
    const path = from.split(/[?#]/)[0] ?? from
    const match = AREA_PATHS.find(([prefix]) => path === prefix || path.startsWith(`${prefix}/`))
    if (match) return match[1]
  }
  return 'CUSTOMER'
}
