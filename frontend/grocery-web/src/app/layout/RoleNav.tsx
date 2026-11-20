import { type FocusEvent, type MouseEvent, useEffect, useId, useState } from 'react'
import { createPortal } from 'react-dom'
import { NavLink } from 'react-router'
import { hasPermission } from '../../shared/auth/permissions'
import type { RoleArea } from '../../shared/auth/roles'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { NAVIGATION, type NavItem } from './navigation'

interface RoleNavProps {
  area: RoleArea
  /** The user's roles; items with a `permission` are hidden from roles that lack it. */
  roles: readonly string[]
  /** Called after a link is clicked (closes the mobile menu). */
  onNavigate?: () => void
  /** Icons only (the desktop sidebar's collapsed mode): names move to tooltips and stay readable by screen readers. */
  collapsed?: boolean
}

interface NavGroup {
  section: string | undefined
  items: NavItem[]
}

/** Consecutive items with the same `section` form one group (areas without sections are a single group). */
function groupBySection(items: NavItem[]): NavGroup[] {
  const groups: NavGroup[] = []
  for (const item of items) {
    const last = groups.at(-1)
    if (last && last.section === item.section) last.items.push(item)
    else groups.push({ section: item.section, items: [item] })
  }
  return groups
}

interface Tip {
  label: string
  top: number
  left: number
}

/**
 * The collapsed menu's name tooltip. It is rendered into `document.body` at fixed coordinates, so the scrolling
 * sidebar never clips it and it always sits above the page (no overlap with the content underneath). It is only a
 * visual aid: each link keeps its name for screen readers.
 */
function NavTooltip({ tip }: { tip: Tip }) {
  return createPortal(
    <div
      aria-hidden="true"
      style={{ top: tip.top, left: tip.left }}
      className="animate-fade-in pointer-events-none fixed z-[60] -translate-y-1/2 rounded-lg bg-slate-900 px-2.5 py-1.5 text-xs font-semibold whitespace-nowrap text-white shadow-lg shadow-slate-900/30 ring-1 ring-white/10"
    >
      <span className="absolute top-1/2 -left-1 size-2 -translate-y-1/2 rotate-45 bg-slate-900" />
      <span className="relative">{tip.label}</span>
    </div>,
    document.body,
  )
}

export function RoleNav({ area, roles, onNavigate, collapsed = false }: RoleNavProps) {
  const id = useId()
  const items = NAVIGATION[area].filter((item) => !item.permission || hasPermission(roles, item.permission))
  const groups = groupBySection(items)
  const [tip, setTip] = useState<Tip | null>(null)

  // A tooltip only belongs to the collapsed menu; a scroll moves the links away from it.
  useEffect(() => {
    if (!collapsed) return
    const hide = () => setTip(null)
    window.addEventListener('scroll', hide, true)
    window.addEventListener('resize', hide)
    return () => {
      window.removeEventListener('scroll', hide, true)
      window.removeEventListener('resize', hide)
      setTip(null)
    }
  }, [collapsed])

  function showTip(label: string) {
    return (event: MouseEvent<HTMLElement> | FocusEvent<HTMLElement>) => {
      if (!collapsed) return
      const box = event.currentTarget.getBoundingClientRect()
      setTip({ label, top: box.top + box.height / 2, left: box.right + 12 })
    }
  }
  const hideTip = () => setTip(null)

  return (
    <nav aria-label="Main navigation" className={collapsed ? 'space-y-3' : 'space-y-5'}>
      {groups.map((group, index) => {
        const headingId = `${id}-section-${index}`
        return (
          <div key={group.section ?? index}>
            {group.section &&
              (collapsed ? (
                // The section names stay for screen readers; a thin line separates the groups.
                <>
                  <p id={headingId} className="sr-only">
                    {group.section}
                  </p>
                  {index > 0 && <div aria-hidden="true" className="mx-3 mb-3 h-px bg-white/10" />}
                </>
              ) : (
                <p id={headingId} className="mb-1.5 px-3 text-[11px] font-semibold tracking-wider text-white/50 uppercase">
                  {group.section}
                </p>
              ))}
            <ul className="space-y-0.5" aria-labelledby={group.section ? headingId : undefined}>
              {group.items.map((item) =>
                item.comingInPhase === undefined ? (
                  <li key={item.to}>
                    <NavLink
                      to={item.to}
                      end={!item.matchNested}
                      onClick={() => {
                        hideTip()
                        onNavigate?.()
                      }}
                      onMouseEnter={showTip(item.label)}
                      onMouseLeave={hideTip}
                      onFocus={showTip(item.label)}
                      onBlur={hideTip}
                      className={({ isActive }) =>
                        cn(
                          // Items with an icon keep the same height: the icon's chip replaces the vertical padding.
                          'group relative flex items-center rounded-xl text-sm font-medium transition-all duration-200',
                          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-300',
                          collapsed ? 'justify-center px-0 py-1.5' : cn('gap-3 px-3', item.icon ? 'py-1' : 'py-2'),
                          // On the dark chrome (`bg-chrome`, AppLayout): white text, a brighter green pill when active.
                          isActive
                            ? 'bg-gradient-to-r from-emerald-600 to-teal-600 text-white shadow-md ring-1 shadow-black/25 ring-white/15'
                            : cn('text-white/80 hover:bg-white/10 hover:text-white', !collapsed && 'hover:translate-x-0.5'),
                        )
                      }
                    >
                      {({ isActive }) => (
                        <>
                          {/* In the collapsed menu the active page keeps a bright marker on the left edge. */}
                          {collapsed && isActive && (
                            <span aria-hidden="true" className="absolute top-1/2 -left-3 h-6 w-1 -translate-y-1/2 rounded-r-full bg-emerald-300" />
                          )}
                          {item.icon ? (
                            // A white chip keeps the colourful icon readable on the dark menu and the active pill.
                            <span
                              aria-hidden="true"
                              className={cn(
                                'flex size-7 shrink-0 items-center justify-center rounded-lg transition-all duration-200',
                                !collapsed && '-ml-1',
                                isActive
                                  ? 'bg-white shadow-sm'
                                  : 'bg-white/90 group-hover:scale-110 group-hover:bg-white group-hover:shadow-sm',
                              )}
                            >
                              <Icon name={item.icon} className="size-5" />
                            </span>
                          ) : (
                            collapsed && (
                              <span aria-hidden="true" className="flex size-7 items-center justify-center rounded-lg bg-white/15 text-xs font-bold">
                                {item.label.charAt(0)}
                              </span>
                            )
                          )}
                          <span className={collapsed ? 'sr-only' : 'truncate'}>{item.label}</span>
                        </>
                      )}
                    </NavLink>
                  </li>
                ) : (
                  <li key={item.to}>
                    <span
                      aria-disabled="true"
                      className={cn('flex items-center rounded-xl py-2 text-sm text-white/40', collapsed ? 'justify-center' : 'justify-between px-3')}
                    >
                      <span className={collapsed ? 'sr-only' : undefined}>{item.label}</span>
                      {!collapsed && (
                        <span className="rounded-full bg-white/10 px-2 py-0.5 text-xs font-medium text-white/60">
                          Phase {item.comingInPhase}
                        </span>
                      )}
                    </span>
                  </li>
                ),
              )}
            </ul>
          </div>
        )
      })}
      {collapsed && tip && <NavTooltip tip={tip} />}
    </nav>
  )
}
