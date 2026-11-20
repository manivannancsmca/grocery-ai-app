import { NavLink } from 'react-router'
import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { TAB_BAR_HEIGHT } from './navigation'

interface Tab {
  label: string
  to: string
  icon: IconName
  /** Also active on nested pages (`/orders/123`). */
  matchNested?: boolean
}

const CUSTOMER_TABS: Tab[] = [
  { label: 'Home', to: '/customer/home', icon: 'home' },
  { label: 'Search', to: '/search', icon: 'search' },
  { label: 'Restaurants', to: '/restaurants', icon: 'cutlery', matchNested: true },
  { label: 'Orders', to: '/orders', icon: 'box', matchNested: true },
  { label: 'Account', to: '/account/profile', icon: 'user', matchNested: true },
]

const GUEST_TABS: Tab[] = [
  { label: 'Home', to: '/', icon: 'home' },
  { label: 'Search', to: '/search', icon: 'search' },
  { label: 'Restaurants', to: '/restaurants', icon: 'cutlery', matchNested: true },
  { label: 'Account', to: '/login', icon: 'user' },
]

/**
 * App-style bottom navigation for shoppers on phones and small tablets (hidden from `md` up, where the sidebar
 * shows): the four or five places a customer goes most, one tap away.
 */
export function ShopperTabBar({ signedIn }: { signedIn: boolean }) {
  const tabs = signedIn ? CUSTOMER_TABS : GUEST_TABS
  return (
    <nav
      aria-label="Quick navigation"
      className="fixed inset-x-0 bottom-0 z-30 border-t border-slate-200 bg-white/95 pb-[env(safe-area-inset-bottom)] shadow-[0_-4px_16px_rgb(15_23_42/0.06)] backdrop-blur md:hidden print:hidden!"
    >
      <ul className={cn('mx-auto grid max-w-lg', tabs.length === 5 ? 'grid-cols-5' : 'grid-cols-4')} style={{ height: TAB_BAR_HEIGHT }}>
        {tabs.map((tab) => (
          <li key={tab.to} className="flex">
            <NavLink
              to={tab.to}
              end={!tab.matchNested}
              className={({ isActive }) =>
                cn(
                  'flex flex-1 flex-col items-center justify-center gap-1 text-[11px] font-semibold transition-colors',
                  isActive ? 'text-emerald-700' : 'text-slate-500 hover:text-slate-800',
                )
              }
            >
              {({ isActive }) => (
                <>
                  <span
                    className={cn(
                      'flex h-7 w-12 items-center justify-center rounded-full transition-colors',
                      isActive && 'bg-emerald-100',
                    )}
                  >
                    <Icon name={tab.icon} className="size-5" />
                  </span>
                  {tab.label}
                </>
              )}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}
