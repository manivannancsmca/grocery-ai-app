import { Link, useNavigate } from 'react-router'
import { useAddresses, useCustomerProfile } from '../../../features/customer/hooks'
import { ProfileForm } from '../../../features/customer/ProfileForm'
import { useMyOrders } from '../../../features/orders/hooks'
import { OrderStatusBadge } from '../../../features/orders/OrderBits'
import { useMyReviews } from '../../../features/reviews/hooks'
import { useAuth } from '../../../shared/auth/useAuth'
import { ProfileGate } from '../../../shared/components/ProfileGate'
import { Icon } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'
import { formatDate, formatPrice, initials } from '../../../shared/utils/format'
import { ShopperPageHeader } from '../components/ShopperPageHeader'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../../features/orders/api').OrderSummary} OrderSummary */
/** @typedef {import('../../../shared/icons').IconName} IconName */

/**
 * Shortcuts to the other account pages — on phones this page is the "Account" tab, so it doubles as the hub.
 * @type {ReadonlyArray<{ to: string, label: string, description: string, icon: IconName, tint: string }>}
 */
const SHORTCUTS = [
  {
    to: '/orders',
    label: 'Orders',
    description: 'Track, pay and reorder',
    icon: 'box',
    tint: 'bg-amber-50 ring-amber-100',
  },
  {
    to: '/account/returns',
    label: 'Returns',
    description: 'Refunds and problems',
    icon: 'return',
    tint: 'bg-violet-50 ring-violet-100',
  },
  {
    to: '/account/reviews',
    label: 'Reviews',
    description: 'Your ratings',
    icon: 'star',
    tint: 'bg-yellow-50 ring-yellow-100',
  },
  {
    to: '/account/addresses',
    label: 'Addresses',
    description: 'Where we deliver',
    icon: 'mapPin',
    tint: 'bg-rose-50 ring-rose-100',
  },
  {
    to: '/notifications',
    label: 'Notifications',
    description: 'Updates and alerts',
    icon: 'bell',
    tint: 'bg-orange-50 ring-orange-100',
  },
  {
    to: '/notifications/preferences',
    label: 'Email settings',
    description: 'What we email you',
    icon: 'mail',
    tint: 'bg-sky-50 ring-sky-100',
  },
  {
    to: '/assistant/approvals',
    label: 'Assistant',
    description: 'Actions to confirm',
    icon: 'sparkle',
    tint: 'bg-fuchsia-50 ring-fuchsia-100',
  },
]

/**
 * The icon of a saved address by its label: home, work, or a pin.
 * @param {string} label
 * @returns {IconName}
 */
function addressIcon(label) {
  const text = label.toLowerCase()
  if (text.includes('home')) return 'home'
  if (text.includes('office') || text.includes('work')) return 'briefcase'
  return 'mapPin'
}

const TILE =
  'group flex h-full items-center gap-3 rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm shadow-slate-900/[0.03] transition-all hover:-translate-y-0.5 hover:border-emerald-200 hover:shadow-md focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

/**
 * A count on the identity card that links to its page ("12 Orders").
 * @param {{ to: string, label: string, value: number | undefined, icon: IconName }} props
 */
function IdentityStat({ to, label, value, icon }) {
  return (
    <Link
      to={to}
      className="group flex items-center gap-3 rounded-2xl bg-white/10 px-3.5 py-3 ring-1 ring-white/20 transition ring-inset hover:bg-white/20 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white"
    >
      <span
        aria-hidden="true"
        className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-white shadow-sm"
      >
        <Icon name={icon} className="size-5" />
      </span>
      <span className="min-w-0">
        <span className="block text-xl leading-tight font-bold tabular-nums">{value ?? '–'}</span>
        <span className="block truncate text-xs font-medium text-emerald-50/90">{label}</span>
      </span>
    </Link>
  )
}

/** @param {{ icon: IconName, label: string, children: ReactNode }} props */
function ContactChip({ icon, label, children }) {
  return (
    <span className="inline-flex max-w-full min-w-0 items-center gap-2 rounded-full bg-white/15 py-1 pr-3 pl-1 text-sm ring-1 ring-white/20 ring-inset">
      <span aria-hidden="true" className="flex size-7 shrink-0 items-center justify-center rounded-full bg-white">
        <Icon name={icon} className="size-4" />
      </span>
      <span className="sr-only">{label}: </span>
      <span className="truncate">{children}</span>
    </span>
  )
}

/** @param {{ title: string, icon: IconName, description?: string, aside?: ReactNode, children: ReactNode, className?: string }} props */
function Card({ title, icon, description, aside, children, className }) {
  const id = `profile-${title.toLowerCase().replace(/[^a-z]+/g, '-')}`
  return (
    <section
      aria-labelledby={id}
      className={cn('animate-fade-up rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm sm:p-6', className)}
    >
      <div className="mb-5 flex items-start justify-between gap-3">
        <div className="flex items-center gap-3">
          <span
            aria-hidden="true"
            className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-50 ring-1 ring-emerald-100"
          >
            <Icon name={icon} className="size-[1.15rem]" />
          </span>
          <div>
            <h2 id={id} className="text-base font-semibold text-slate-900">
              {title}
            </h2>
            {description && <p className="text-sm text-slate-500">{description}</p>}
          </div>
        </div>
        {aside}
      </div>
      {children}
    </section>
  )
}

/**
 * The customer's most recent order, one click from its page (or a nudge to start shopping).
 * @param {{ order: OrderSummary | undefined, loading: boolean }} props
 */
function LatestOrder({ order, loading }) {
  return (
    <Card
      title="Latest order"
      icon="receipt"
      aside={
        <Link
          to="/orders"
          className="rounded-full px-2.5 py-1 text-sm font-semibold text-emerald-700 hover:bg-emerald-50 focus-visible:outline-2 focus-visible:outline-emerald-600"
        >
          All orders
        </Link>
      }
    >
      {order ? (
        <Link
          to={`/orders/${encodeURIComponent(order.id)}`}
          className="group flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl bg-slate-50/80 p-3.5 ring-1 ring-slate-100 transition-colors hover:bg-emerald-50/60 focus-visible:outline-2 focus-visible:outline-emerald-600"
        >
          <span
            aria-hidden="true"
            className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-white shadow-xs ring-1 ring-slate-200/70"
          >
            <Icon name={order.type === 'RESTAURANT' ? 'cloche' : 'basket'} className="size-5" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm font-semibold text-slate-900">{order.store.name}</span>
            <span className="block truncate text-xs text-slate-500">
              {order.firstItemName}
              {order.itemCount > 1 && ` and ${order.itemCount - 1} more`} · {formatDate(order.createdAt)} ·{' '}
              <span className="font-mono">{order.orderNumber}</span>
            </span>
          </span>
          <OrderStatusBadge status={order.status} />
          <span className="text-sm font-bold text-slate-900 tabular-nums">
            {formatPrice(order.total, order.currency)}
          </span>
          <Icon
            name="chevronRight"
            className="size-4 text-slate-400 transition-transform group-hover:translate-x-0.5"
          />
        </Link>
      ) : (
        <p className="text-sm text-slate-600">
          {loading ? 'Loading your orders…' : 'No orders yet. Stores near you are on the home page.'}
        </p>
      )}
    </Card>
  )
}

export function ProfilePage() {
  const profile = useCustomerProfile()
  const addresses = useAddresses()
  const orders = useMyOrders({ page: 0, size: 1 })
  const reviews = useMyReviews({ page: 0, size: 1 })
  const { logout } = useAuth()
  const navigate = useNavigate()

  async function signOut() {
    await logout()
    await navigate('/login', { replace: true })
  }

  return (
    <div>
      <ShopperPageHeader
        icon="user"
        eyebrow="Your account"
        title="My profile"
        description="Your details for orders and deliveries, and everything about your account."
      />
      <ProfileGate query={profile} notFoundCode="CUSTOMER_PROFILE_NOT_FOUND">
        {(data) => (
          <div className="space-y-6">
            <section
              aria-labelledby="profile-identity"
              className="animate-fade-up relative isolate overflow-hidden rounded-3xl bg-gradient-to-br from-emerald-600 via-emerald-700 to-teal-700 p-5 text-white shadow-lg shadow-emerald-900/15 sm:p-7"
            >
              <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10">
                <div className="absolute -top-24 -right-20 size-72 rounded-full bg-emerald-300/25 blur-3xl" />
                <div className="absolute -bottom-28 left-1/4 size-72 rounded-full bg-amber-300/20 blur-3xl" />
                <div className="absolute inset-0 [background-image:radial-gradient(rgb(255_255_255/0.12)_1px,transparent_1px)] [background-size:22px_22px] [mask-image:linear-gradient(to_bottom,black,transparent_85%)]" />
              </div>
              <div className="flex flex-col gap-5 lg:flex-row lg:items-center lg:justify-between">
                <div className="flex min-w-0 items-center gap-4 sm:gap-5">
                  <span
                    aria-hidden="true"
                    className="flex size-20 shrink-0 items-center justify-center rounded-3xl bg-white text-2xl font-bold text-emerald-700 shadow-lg ring-4 shadow-emerald-950/20 ring-white/30"
                  >
                    {initials(data.fullName)}
                  </span>
                  <div className="min-w-0">
                    <h2 id="profile-identity" className="text-2xl font-bold tracking-tight break-words">
                      {data.fullName || 'Your account'}
                    </h2>
                    <p className="mt-0.5 inline-flex items-center gap-1.5 text-sm text-emerald-50/90">
                      <Icon name="calendar" className="size-3.5" />
                      Member since {formatDate(data.createdAt)}
                    </p>
                    <div className="mt-3 flex min-w-0 flex-wrap gap-2">
                      <ContactChip icon="mail" label="Email">
                        {data.email}
                      </ContactChip>
                      <ContactChip icon="phone" label="Mobile number">
                        {data.phone || 'No mobile number yet'}
                      </ContactChip>
                    </div>
                  </div>
                </div>
                <nav aria-label="Account summary" className="grid grid-cols-3 gap-2 sm:gap-3 lg:w-[30rem]">
                  <IdentityStat to="/orders" label="Orders" value={orders.data?.totalElements} icon="box" />
                  <IdentityStat
                    to="/account/addresses"
                    label="Addresses"
                    value={addresses.data?.length}
                    icon="mapPin"
                  />
                  <IdentityStat to="/account/reviews" label="Reviews" value={reviews.data?.totalElements} icon="star" />
                </nav>
              </div>
            </section>

            <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
              <div className="min-w-0 space-y-6 lg:col-span-2">
                <Card
                  title="Personal details"
                  icon="user"
                  description="Used on your orders, and by delivery partners to reach you."
                >
                  <ProfileForm key={data.updatedAt} profile={data} />
                </Card>
                <LatestOrder order={orders.data?.content[0]} loading={orders.isPending} />
              </div>

              <Card
                title="Saved addresses"
                icon="home"
                description={addresses.data ? `${addresses.data.length} saved` : undefined}
                aside={
                  <Link
                    to="/account/addresses"
                    className="rounded-full px-2.5 py-1 text-sm font-semibold text-emerald-700 hover:bg-emerald-50 focus-visible:outline-2 focus-visible:outline-emerald-600"
                  >
                    Manage
                  </Link>
                }
              >
                {addresses.data && addresses.data.length > 0 ? (
                  <ul className="space-y-2.5">
                    {[...addresses.data]
                      .sort((a, b) => Number(b.isDefault) - Number(a.isDefault))
                      .slice(0, 3)
                      .map((address) => (
                        <li
                          key={address.id}
                          className="flex gap-3 rounded-xl bg-slate-50/80 p-3 text-sm ring-1 ring-slate-100"
                        >
                          <span
                            aria-hidden="true"
                            className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-white shadow-xs ring-1 ring-slate-200/70"
                          >
                            <Icon name={addressIcon(address.label)} className="size-4" />
                          </span>
                          <div className="min-w-0">
                            <p className="flex flex-wrap items-center gap-2 font-semibold text-slate-900">
                              {address.label}
                              {address.isDefault && (
                                <span className="rounded-full bg-emerald-100 px-2 py-px text-xs font-semibold text-emerald-800">
                                  Default
                                </span>
                              )}
                            </p>
                            <p className="mt-0.5 text-slate-600">
                              {[address.line1, address.line2].filter(Boolean).join(', ')}
                            </p>
                            <p className="text-xs text-slate-500">
                              {address.city} {address.postalCode}
                            </p>
                          </div>
                        </li>
                      ))}
                    {addresses.data.length > 3 && (
                      <li>
                        <Link
                          to="/account/addresses"
                          className="text-sm font-semibold text-emerald-700 hover:underline"
                        >
                          {addresses.data.length - 3} more
                        </Link>
                      </li>
                    )}
                  </ul>
                ) : (
                  <p className="text-sm text-slate-600">
                    {addresses.isPending
                      ? 'Loading your addresses…'
                      : 'No delivery address yet. Add one so stores near you can deliver.'}
                  </p>
                )}
              </Card>
            </div>

            <nav aria-labelledby="profile-shortcuts" className="animate-fade-up">
              <h2 id="profile-shortcuts" className="mb-3 text-base font-semibold text-slate-900">
                Your account
              </h2>
              <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
                {SHORTCUTS.map((item) => (
                  <li key={item.to}>
                    <Link to={item.to} className={TILE}>
                      <span
                        aria-hidden="true"
                        className={cn(
                          'flex size-11 shrink-0 items-center justify-center rounded-xl ring-1 transition-transform group-hover:scale-105',
                          item.tint,
                        )}
                      >
                        <Icon name={item.icon} className="size-5" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block text-sm font-semibold text-slate-900">{item.label}</span>
                        <span className="block text-xs text-slate-500">{item.description}</span>
                      </span>
                      <Icon
                        name="chevronRight"
                        className="size-4 text-slate-400 transition-transform group-hover:translate-x-0.5"
                      />
                    </Link>
                  </li>
                ))}
                <li>
                  <button
                    type="button"
                    onClick={() => void signOut()}
                    className={cn(TILE, 'w-full text-left hover:border-rose-200')}
                  >
                    <span
                      aria-hidden="true"
                      className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-rose-50 ring-1 ring-rose-100 transition-transform group-hover:scale-105"
                    >
                      <Icon name="arrowLeft" className="size-5" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block text-sm font-semibold text-rose-700">Sign out</span>
                      <span className="block text-xs text-slate-500">Leave this device</span>
                    </span>
                  </button>
                </li>
              </ul>
            </nav>
          </div>
        )}
      </ProfileGate>
    </div>
  )
}
