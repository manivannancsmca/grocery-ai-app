import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import { AREA_META, roleLabel } from '../../shared/auth/roles'
import { useAuth } from '../../shared/auth/useAuth'
import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { initials } from '../../shared/utils/format'

interface UserMenuProps {
  /** Ends the session; the layout (which stays mounted) then navigates to /login. */
  onSignOut: () => Promise<void>
}

interface MenuEntry {
  to: string
  icon: IconName
  label: string
  /** One short line under the label. */
  hint: string
}

/** The shortcuts in the menu: a customer's own pages, then what every signed-in user has. */
const CUSTOMER_ENTRIES: readonly MenuEntry[] = [
  { to: '/orders', icon: 'box', label: 'My orders', hint: 'Track, reorder and return' },
  { to: '/account/profile', icon: 'user', label: 'My profile', hint: 'Name and phone number' },
  { to: '/account/addresses', icon: 'mapPin', label: 'Addresses', hint: 'Where we deliver to' },
  { to: '/assistant/approvals', icon: 'sparkle', label: 'Assistant actions', hint: 'What the AI asked to do' },
]
const COMMON_ENTRIES: readonly MenuEntry[] = [
  { to: '/notifications', icon: 'bell', label: 'Notifications', hint: 'Updates and alerts' },
  { to: '/account/password', icon: 'lock', label: 'Change password', hint: 'Signs out your other devices' },
]

function MenuLink({ entry, onClick }: { entry: MenuEntry; onClick: () => void }) {
  const labelId = useId()
  const hintId = useId()
  return (
    <li>
      {/* Named by its label ("My orders"); the hint is read as its description. */}
      <Link
        to={entry.to}
        onClick={onClick}
        aria-labelledby={labelId}
        aria-describedby={hintId}
        className="group/item flex items-center gap-3 rounded-xl px-2.5 py-2 text-slate-700 transition-colors duration-150 hover:bg-emerald-50/80 focus-visible:bg-emerald-50 focus-visible:outline-none"
      >
        <span
          aria-hidden="true"
          className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-white shadow-sm ring-1 ring-slate-200/80 transition-transform duration-150 group-hover/item:scale-105 group-hover/item:ring-emerald-200 motion-reduce:transition-none"
        >
          <Icon name={entry.icon} className="size-5" />
        </span>
        <span className="min-w-0 flex-1">
          <span id={labelId} className="block text-sm font-semibold text-slate-800 group-hover/item:text-emerald-800">
            {entry.label}
          </span>
          <span id={hintId} className="block truncate text-xs text-slate-500">
            {entry.hint}
          </span>
        </span>
        <Icon
          name="chevronRight"
          className="size-3.5 text-slate-300 transition-transform duration-150 group-hover/item:translate-x-0.5 group-hover/item:text-emerald-600 motion-reduce:transition-none"
        />
      </Link>
    </li>
  )
}

/** Avatar button that opens a card with the user's name, email and roles, their shortcuts and sign-out. */
export function UserMenu({ onSignOut }: UserMenuProps) {
  const { user, area } = useAuth()
  const [open, setOpen] = useState(false)
  const [signingOut, setSigningOut] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const menuId = useId()

  // Close on a click outside or on Escape (focus goes back to the avatar button).
  useEffect(() => {
    if (!open) return
    function onPointerDown(event: PointerEvent) {
      if (!containerRef.current?.contains(event.target as Node)) setOpen(false)
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false)
        buttonRef.current?.focus()
      }
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  if (!user) return null

  function signOut() {
    setSigningOut(true)
    void onSignOut()
  }

  const close = () => setOpen(false)
  const entries = area === 'CUSTOMER' ? [...CUSTOMER_ENTRIES, ...COMMON_ENTRIES] : COMMON_ENTRIES

  return (
    <div ref={containerRef} className="relative">
      <button
        ref={buttonRef}
        type="button"
        aria-haspopup="true"
        aria-expanded={open}
        aria-controls={menuId}
        onClick={() => setOpen((value) => !value)}
        className={cn(
          'flex items-center gap-2 rounded-full py-1 pr-1 pl-1 text-sm transition-colors hover:bg-white/10 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white sm:pr-2.5',
          open && 'bg-white/10',
        )}
      >
        <span
          className="flex size-8 items-center justify-center rounded-full bg-gradient-to-br from-emerald-400 to-teal-600 text-xs font-semibold text-white shadow-sm ring-2 shadow-emerald-600/30 ring-white"
          aria-hidden="true"
        >
          {initials(user.fullName)}
        </span>
        <span className="hidden max-w-40 truncate font-medium text-white sm:block">{user.fullName}</span>
        <Icon
          name="chevronRight"
          className={cn('hidden size-3 text-white/70 transition-transform duration-200 sm:block motion-reduce:transition-none', open ? '-rotate-90' : 'rotate-90')}
        />
        <span className="sr-only">Account menu for {user.fullName}</span>
      </button>

      {open && (
        <div
          id={menuId}
          className="animate-pop-in absolute top-full right-0 z-40 mt-2.5 w-80 max-w-[calc(100vw-1.5rem)] origin-top-right overflow-hidden rounded-2xl bg-white shadow-2xl ring-1 shadow-slate-900/20 ring-slate-900/10"
        >
          {/* Who is signed in: a colourful card with the avatar, name, email and roles. */}
          <div className="relative overflow-hidden bg-gradient-to-br from-emerald-600 via-teal-600 to-sky-700 px-4 pt-4 pb-3.5 text-white">
            <span aria-hidden="true" className="absolute -top-10 -right-8 size-28 rounded-full bg-white/15 blur-2xl" />
            <span aria-hidden="true" className="absolute -bottom-12 left-10 size-24 rounded-full bg-amber-300/25 blur-2xl" />
            <div className="relative flex items-center gap-3">
              <span
                aria-hidden="true"
                className="flex size-12 shrink-0 items-center justify-center rounded-2xl bg-white text-base font-bold text-emerald-700 shadow-md ring-2 ring-white/40"
              >
                {initials(user.fullName)}
              </span>
              <div className="min-w-0">
                <p className="truncate text-base leading-tight font-semibold">{user.fullName}</p>
                <p className="truncate text-xs text-white/80">{user.email}</p>
              </div>
            </div>
            <ul aria-label="Roles" className="relative mt-3 flex flex-wrap gap-1.5">
              {area && area !== 'CUSTOMER' && (
                <li className="inline-flex items-center gap-1 rounded-full bg-white px-2 py-0.5 text-[11px] font-semibold text-emerald-800 shadow-sm">
                  {AREA_META[area].label} workspace
                </li>
              )}
              {user.roles.map((role) => (
                <li key={role} className="rounded-full bg-white/15 px-2 py-0.5 text-[11px] font-medium ring-1 ring-white/25 ring-inset">
                  {roleLabel(role)}
                </li>
              ))}
            </ul>
          </div>

          <ul className="space-y-0.5 p-2">
            {entries.map((entry) => (
              <MenuLink key={entry.to} entry={entry} onClick={close} />
            ))}
          </ul>

          <div className="border-t border-slate-100 bg-slate-50/70 p-2">
            <button
              type="button"
              onClick={signOut}
              disabled={signingOut}
              className="group/out flex w-full items-center gap-3 rounded-xl px-2.5 py-2 text-left text-sm font-semibold text-red-600 transition-colors hover:bg-red-50 focus-visible:bg-red-50 focus-visible:outline-none disabled:opacity-60"
            >
              <span aria-hidden="true" className="flex size-9 items-center justify-center rounded-xl bg-red-50 ring-1 ring-red-100 group-hover/out:bg-white">
                <Icon name="arrowRight" className="size-4" />
              </span>
              {signingOut ? 'Signing out…' : 'Sign out'}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
