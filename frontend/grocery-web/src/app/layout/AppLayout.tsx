import { useEffect, useState } from 'react'
import { Link, Navigate, Outlet, useLocation, useNavigate } from 'react-router'
import { AssistantHeaderButton } from '../../features/ai/AssistantHeaderButton'
import { AssistantProvider } from '../../features/ai/AssistantProvider'
import { STAKEHOLDERS } from '../../features/auth/stakeholders'
import { CartButton } from '../../features/cart/CartButton'
import { SearchBox } from '../../features/catalog/SearchBox'
import { NotificationBell } from '../../features/notifications/NotificationBell'
import { DeliverToButton } from '../../features/stores/DeliverToButton'
import { DeliveryLocationDialog } from '../../features/stores/DeliveryLocationProvider'
import { AREA_META, type RoleArea } from '../../shared/auth/roles'
import { useAuth } from '../../shared/auth/useAuth'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { TAB_BAR_HEIGHT } from './navigation'
import { RoleNav } from './RoleNav'
import { ShopperTabBar } from './ShopperTabBar'
import { useSidebarCollapsed } from './useSidebarCollapsed'
import { UserMenu } from './UserMenu'

function MenuIcon({ open }: { open: boolean }) {
  return <Icon name={open ? 'close' : 'menu'} className="size-6" />
}

/**
 * The brand in the top bar. `compactOnSmallPhones`: the shoppers' top bar is the busiest (search, cart, Ask AI, bell,
 * account), so below 420 px it shows the mark only — the name stays for screen readers — and nothing overflows.
 */
function Logo({ to, compactOnSmallPhones = false }: { to: string; compactOnSmallPhones?: boolean }) {
  return (
    <Link to={to} className="flex items-center gap-2 font-bold text-white">
      <span className="flex size-8 items-center justify-center rounded-lg bg-emerald-500 text-white ring-1 ring-white/20" aria-hidden="true">
        <svg className="size-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
          <path d="M5 16c0-6 4-9 12-9-1 7-4 10-9 10-1 0-2-.3-3-1" />
          <path d="M5 18c2-3 5-5 8-6" />
        </svg>
      </span>
      <span className={cn('text-lg tracking-tight', compactOnSmallPhones && 'max-[419px]:sr-only')}>FreshCart</span>
    </Link>
  )
}

/** The workspace a business user is in (Vendor, Restaurant, Delivery, Admin), above their menu; an icon when compact. */
function AreaBadge({ area, compact = false }: { area: RoleArea; compact?: boolean }) {
  if (compact) {
    return (
      <div className="mb-4 flex justify-center">
        <span className="flex size-11 items-center justify-center rounded-2xl bg-gradient-to-br from-emerald-500 to-teal-600 shadow-md ring-1 shadow-black/20 ring-white/15">
          <span aria-hidden="true" className="flex size-8 items-center justify-center rounded-xl bg-white shadow-sm">
            <Icon name={STAKEHOLDERS[area].icon} className="size-5" />
          </span>
          <span className="sr-only">{AREA_META[area].label} workspace</span>
        </span>
      </div>
    )
  }
  return (
    <div className="relative mb-5 overflow-hidden rounded-2xl bg-gradient-to-br from-emerald-600 via-emerald-600 to-teal-700 px-3 py-3 text-white shadow-md ring-1 shadow-black/20 ring-white/10">
      <span aria-hidden="true" className="absolute -top-6 -right-6 size-20 rounded-full bg-white/15 blur-xl" />
      <span aria-hidden="true" className="absolute -bottom-8 left-6 size-16 rounded-full bg-amber-300/25 blur-xl" />
      <p className="relative flex items-center gap-2.5">
        <span aria-hidden="true" className="flex size-9 items-center justify-center rounded-xl bg-white shadow-sm">
          <Icon name={STAKEHOLDERS[area].icon} className="size-5" />
        </span>
        <span className="min-w-0">
          <span className="block text-sm leading-tight font-semibold">{AREA_META[area].label}</span>
          <span className="block text-[11px] text-emerald-100">Workspace</span>
        </span>
      </p>
    </div>
  )
}

function GuestActions() {
  const { pathname } = useLocation()
  return (
    <div className="flex items-center gap-2">
      {pathname !== '/login' && (
        <Link to="/login" className="rounded-lg px-3 py-2 text-sm font-medium text-white/90 hover:bg-white/10 hover:text-white">
          Sign in
        </Link>
      )}
      {pathname !== '/register' && (
        <Link
          to="/register"
          className="rounded-lg bg-white px-3 py-2 text-sm font-semibold text-emerald-800 shadow-sm hover:bg-emerald-50"
        >
          Create account
        </Link>
      )}
    </div>
  )
}

/**
 * The app chrome: a deep emerald top bar that brightens to teal on the right, and a left menu that darkens downwards.
 * Both start from the same colour (`--color-chrome` in index.css), so they meet without a seam; white text and icons
 * keep at least 5:1 contrast on every part of them. `bg-chrome` stays as the fallback under the gradient.
 */
const CHROME_TOP_BAR = 'bg-chrome bg-gradient-to-r from-chrome via-emerald-800 to-teal-700'
const CHROME_SIDE_BAR = 'bg-chrome bg-gradient-to-b from-chrome via-emerald-950 to-teal-950'

/** The page frame shared by the header and the content, so they line up: the full browser width on every screen. */
const PAGE_FRAME = 'w-full'

/**
 * Sign-in, registration, the suspended screen and the password reset screens are full-screen designs of their own: no
 * top bar, menu or tab bar.
 */
const AUTH_SCREENS = new Set(['/login', '/register', '/account-suspended', '/forgot-password', '/reset-password'])

/** Pages with their own big search box (or where a search box would distract). */
const PAGES_WITHOUT_HEADER_SEARCH = new Set(['/', '/customer/home', '/search', '/login', '/register', '/account-suspended'])

/** Compact product search for shoppers: a combobox on larger screens, a link to /search on phones. */
function HeaderSearch() {
  const { pathname } = useLocation()
  if (PAGES_WITHOUT_HEADER_SEARCH.has(pathname)) return <div className="flex-1" />
  return (
    <div className="flex flex-1 justify-end md:justify-center">
      <SearchBox size="sm" label="Search products" placeholder="Search products…" className="hidden w-full max-w-md md:block" />
      <Link
        to="/search"
        aria-label="Search products"
        className="rounded-lg p-2 text-white/80 hover:bg-white/10 hover:text-white md:hidden"
      >
        <Icon name="search" className="size-5" />
      </Link>
    </div>
  )
}

export function AppLayout() {
  const { status, area, roles, logout } = useAuth()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const [sidebarCollapsed, toggleSidebar] = useSidebarCollapsed()
  const closeMobileMenu = () => setMobileMenuOpen(false)
  const signedIn = status === 'authenticated' && area !== null
  const authScreen = AUTH_SCREENS.has(pathname)
  const shopper = !authScreen && status !== 'initializing' && (area === null || area === 'CUSTOMER')

  // Shoppers get a bottom tab bar on phones. `--tabbar-h` on <html> lets the Ask AI button and toasts (rendered
  // outside this layout) sit above it.
  useEffect(() => {
    if (!shopper) return
    const root = document.documentElement
    root.style.setProperty('--tabbar-h', TAB_BAR_HEIGHT)
    return () => {
      root.style.removeProperty('--tabbar-h')
    }
  }, [shopper])

  async function signOut() {
    await logout()
    setMobileMenuOpen(false)
    // Replace any "come back to" state the guards added: the next person signs in fresh.
    await navigate('/login', { replace: true })
  }

  if (authScreen) {
    return (
      <main className="animate-fade-in">
        {/* A suspension during sign-in or registration leads to the suspended screen. */}
        {status === 'suspended' && pathname !== '/account-suspended' && <Navigate to="/account-suspended" replace />}
        <Outlet />
      </main>
    )
  }

  return (
    // Phase 11: the "Ask AI" launcher and assistant panel on every page for customers and admins. From `md` up the
    // launcher is docked at the bottom of the left menu (signed-in users, the only ones with an assistant, have it).
    <AssistantProvider dock={signedIn ? (sidebarCollapsed ? 'rail' : 'sidebar') : undefined}>
      <div className="flex min-h-screen flex-col">
        {/* A suspension during the session (403 ACCOUNT_SUSPENDED, live `session` REVOKED) leaves public pages too. */}
        {status === 'suspended' && pathname !== '/account-suspended' && <Navigate to="/account-suspended" replace />}
        {/* The top bar and the left menu share the chrome colours (CHROME_TOP_BAR, CHROME_SIDE_BAR). */}
        <header className={cn('sticky top-0 z-30 border-b border-chrome-edge text-white shadow-sm shadow-black/10 print:hidden!', CHROME_TOP_BAR)}>
          {/* A thin brand-colour line along the bottom edge. */}
          <div aria-hidden="true" className="absolute inset-x-0 -bottom-px h-px bg-gradient-to-r from-transparent via-emerald-400/60 to-transparent" />
          <div className={cn(PAGE_FRAME, 'flex h-16 items-center justify-between gap-3 px-4 sm:px-6 lg:px-8')}>
            <div className="flex items-center gap-2">
              {signedIn && (
                <button
                  type="button"
                  className="-ml-2 rounded-lg p-2 text-white/80 hover:bg-white/10 hover:text-white md:hidden"
                  aria-label={mobileMenuOpen ? 'Close menu' : 'Open menu'}
                  aria-expanded={mobileMenuOpen}
                  aria-controls="mobile-navigation"
                  onClick={() => setMobileMenuOpen((open) => !open)}
                >
                  <MenuIcon open={mobileMenuOpen} />
                </button>
              )}
              <Logo to={signedIn ? AREA_META[area].homePath : '/'} compactOnSmallPhones={shopper} />
              {/* Where products, stores and restaurants are shown for (phones: on the storefront and search instead). */}
              {shopper && <DeliverToButton className="ml-2 hidden max-w-[13rem] md:flex lg:max-w-[16rem]" />}
            </div>
            {shopper ? <HeaderSearch /> : <div className="flex-1" />}
            {signedIn ? (
              <div className="flex items-center gap-1 sm:gap-2">
                {area === 'CUSTOMER' && <CartButton />}
                <AssistantHeaderButton />
                <NotificationBell />
                <UserMenu onSignOut={signOut} />
              </div>
            ) : status === 'anonymous' ? (
              <GuestActions />
            ) : null}
          </div>
          {signedIn && mobileMenuOpen && (
            <div id="mobile-navigation" className={cn('animate-pop-in max-h-[calc(100vh-4rem)] overflow-y-auto border-t border-white/10 px-4 py-3 md:hidden', CHROME_SIDE_BAR)}>
              {area !== 'CUSTOMER' && <AreaBadge area={area} />}
              <RoleNav area={area} roles={roles} onNavigate={closeMobileMenu} />
            </div>
          )}
        </header>

        <div className={cn(PAGE_FRAME, 'flex flex-1')}>
          {signedIn && (
            <aside
              className={cn(
                'hidden shrink-0 transition-[width] duration-300 ease-out motion-reduce:transition-none md:block print:hidden!',
                sidebarCollapsed ? 'w-20' : 'w-64',
              )}
            >
              {/*
                The menu is fixed to the window, from under the top bar (h-16 and its 1px border) to the bottom edge; the
                aside only keeps its width free next to the page. However long a page is, or whatever on it reaches past
                its end, the menu never moves or ends early while the page scrolls (as a sticky menu would: it cannot
                leave its row). Only the list of links scrolls inside it. z-30 keeps the toggle's outer half above the
                page next to it.
              */}
              <div
                className={cn(
                  'fixed top-[calc(4rem+1px)] bottom-0 left-0 z-30 flex flex-col border-r border-chrome-edge transition-[width] duration-300 ease-out motion-reduce:transition-none',
                  CHROME_SIDE_BAR,
                  sidebarCollapsed ? 'w-20' : 'w-64',
                )}
              >
                {/*
                  The collapse/expand handle sits on the menu's right edge, half outside it, in its own strip at the top
                  (the menu starts below it, so it never covers the workspace card or the current item); it stays put
                  while the menu scrolls. The chevron points where the menu goes: left to collapse, right to expand.
                */}
                <button
                  type="button"
                  onClick={toggleSidebar}
                  aria-expanded={!sidebarCollapsed}
                  aria-controls="desktop-navigation"
                  aria-label={sidebarCollapsed ? 'Expand menu' : 'Collapse menu'}
                  title={sidebarCollapsed ? 'Expand menu' : 'Collapse menu'}
                  className="absolute top-2.5 -right-3.5 flex size-7 items-center justify-center rounded-full bg-white text-emerald-700 shadow-md ring-1 shadow-black/20 ring-slate-200 transition-[transform,background-color] duration-200 hover:scale-110 hover:bg-emerald-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-400 motion-reduce:transition-none"
                >
                  <Icon
                    name="chevronRight"
                    className={cn('size-4 transition-transform duration-300 motion-reduce:transition-none', !sidebarCollapsed && 'rotate-180')}
                  />
                </button>
                {/*
                  `relative` makes the list the containing block of the collapsed menu's screen-reader section names
                  (`sr-only`, absolutely positioned): without it they escape its scroll box and lengthen the page.
                */}
                <div id="desktop-navigation" className="relative min-h-0 flex-1 overflow-x-hidden overflow-y-auto px-3 pt-11 pb-4">
                  {area !== 'CUSTOMER' && <AreaBadge area={area} compact={sidebarCollapsed} />}
                  <RoleNav area={area} roles={roles} collapsed={sidebarCollapsed} />
                </div>
                {/* The docked "Ask AI" button's slot (`--assistant-space`, 0 without an assistant): the menu scrolls above it. */}
                <div aria-hidden="true" className="h-[var(--assistant-space,0px)] shrink-0" />
              </div>
            </aside>
          )}
          {/*
            For shoppers on phones the bottom padding keeps the end of every page clear of the tab bar: the last row,
            button or pager can always be scrolled above it. The "Ask AI" button never covers the page: on phones it
            sits in the top bar, from `md` in the left menu.
          */}
          <main
            className={cn(
              'animate-fade-in min-w-0 flex-1 px-4 pt-6 pb-6 sm:px-6 lg:px-8',
              shopper && 'pb-[calc(var(--tabbar-h,4.25rem)+1.5rem)] md:pb-6',
            )}
          >
            <Outlet />
          </main>
        </div>
        {shopper && <ShopperTabBar signedIn={signedIn} />}
        {shopper && <DeliveryLocationDialog />}
      </div>
    </AssistantProvider>
  )
}
