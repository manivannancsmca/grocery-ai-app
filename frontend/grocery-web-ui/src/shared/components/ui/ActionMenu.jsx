import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Icon } from '../../icons'
import { cn } from '../../utils/cn'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../icons').IconName} IconName */

/**
 * @typedef {object} ActionMenuItem
 * @property {string} label
 * @property {() => void} onSelect
 * @property {IconName} [icon]
 * @property {'default' | 'danger'} [tone] Red text: destructive actions (suspend, delete…), which should still ask for confirmation themselves.
 * @property {boolean} [disabled]
 * @property {string} [hint] A short second line under the label (read as the item's description).
 */

/**
 * @typedef {object} ActionMenuProps
 * @property {string} label Accessible name of the button, e.g. "Actions for FreshMart".
 * @property {readonly ActionMenuItem[]} items
 * @property {string} [className]
 * @property {ReactNode} [trigger] Visible button content (e.g. an icon and "Export"); without it the button is the round "⋯".
 * @property {boolean} [disabled] Disables the button (e.g. while the action it offers is running).
 * @property {ReactNode} [footer] A note under the items (e.g. what the choices have in common).
 * @property {number} [width] Menu width in pixels (default 208).
 */

const MENU_WIDTH = 208

/**
 * @typedef {object} Position
 * @property {number} top
 * @property {number} left
 * @property {boolean} above Opens upwards when there is no room below.
 */

/**
 * A row's "⋯" menu (WAI-ARIA menu button): Enter / Space / ↓ open it on the first item, ↑ / ↓ / Home / End move,
 * Escape closes it and returns to the button, Tab or a click elsewhere closes it. The list is rendered into
 * `document.body` at fixed coordinates, so a table's scrolling container never clips it.
 * @param {ActionMenuProps} props
 */
export function ActionMenu({ label, items, className, trigger, disabled, footer, width = MENU_WIDTH }) {
  const id = useId()
  const buttonRef = useRef(null)
  const menuRef = useRef(null)
  const [open, setOpen] = useState(false)
  const [position, setPosition] = useState(null)
  const enabled = items.filter((item) => !item.disabled)

  const close = useCallback((returnFocus) => {
    setOpen(false)
    setPosition(null)
    if (returnFocus) buttonRef.current?.focus()
  }, [])

  const place = useCallback(() => {
    const box = buttonRef.current?.getBoundingClientRect()
    if (!box) return
    const estimatedHeight = items.reduce((sum, item) => sum + (item.hint ? 56 : 40), 12) + (footer ? 64 : 0)
    const above = box.bottom + estimatedHeight + 8 > window.innerHeight && box.top > estimatedHeight + 8
    const left = Math.max(8, Math.min(box.right - width, window.innerWidth - width - 8))
    setPosition({ top: above ? box.top - 6 : box.bottom + 6, left, above })
  }, [items, footer, width])

  useLayoutEffect(() => {
    if (open) place()
  }, [open, place])

  // Once it is placed (rendered): focus the first item; close on a click outside, a scroll or a resize.
  const placed = open && position !== null
  useEffect(() => {
    if (!placed) return
    const first = menuRef.current?.querySelector('[role="menuitem"]:not([aria-disabled="true"])')
    first?.focus()
    const onPointer = (event) => {
      const target = event.target
      if (!menuRef.current?.contains(target) && !buttonRef.current?.contains(target)) close(false)
    }
    const onScroll = (event) => {
      if (!menuRef.current?.contains(event.target)) close(false)
    }
    const onResize = () => close(false)
    document.addEventListener('pointerdown', onPointer)
    window.addEventListener('scroll', onScroll, true)
    window.addEventListener('resize', onResize)
    return () => {
      document.removeEventListener('pointerdown', onPointer)
      window.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('resize', onResize)
    }
  }, [placed, close])

  function onButtonKeyDown(event) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      setOpen(true)
    }
  }

  function onMenuKeyDown(event) {
    const nodes = Array.from(menuRef.current?.querySelectorAll('[role="menuitem"]:not([aria-disabled="true"])') ?? [])
    const index = nodes.indexOf(document.activeElement)
    let next
    if (event.key === 'ArrowDown') next = nodes[(index + 1) % nodes.length]
    else if (event.key === 'ArrowUp') next = nodes[(index - 1 + nodes.length) % nodes.length]
    else if (event.key === 'Home') next = nodes[0]
    else if (event.key === 'End') next = nodes[nodes.length - 1]
    else if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      close(true)
      return
    } else if (event.key === 'Tab') {
      close(false)
      return
    } else return
    event.preventDefault()
    next?.focus()
  }

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? `${id}-menu` : undefined}
        disabled={disabled || enabled.length === 0}
        onClick={() => (open ? close(false) : setOpen(true))}
        onKeyDown={onButtonKeyDown}
        className={cn(
          trigger
            ? 'inline-flex h-10 items-center justify-center gap-2 rounded-xl bg-white px-3.5 text-sm font-semibold text-slate-700 shadow-sm ring-1 ring-slate-200 transition-all duration-200 ring-inset'
            : 'inline-flex size-9 items-center justify-center rounded-xl text-slate-500 ring-1 ring-slate-200 transition-all duration-200 ring-inset',
          'hover:bg-emerald-50 hover:text-emerald-700 hover:ring-emerald-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
          'disabled:cursor-not-allowed disabled:opacity-60',
          open && 'bg-emerald-50 text-emerald-700 ring-emerald-300',
          className,
        )}
      >
        {trigger ?? <Icon name="more" className="size-5" />}
      </button>
      {open &&
        position &&
        createPortal(
          <div
            ref={menuRef}
            id={`${id}-menu`}
            role="menu"
            aria-label={label}
            onKeyDown={onMenuKeyDown}
            style={{ top: position.top, left: position.left, width }}
            className={cn(
              'animate-pop-in fixed z-[70] overflow-hidden rounded-2xl bg-white p-1.5 shadow-xl ring-1 shadow-slate-900/15 ring-slate-200',
              position.above && '-translate-y-full',
            )}
          >
            {items.map((item, index) => (
              <button
                key={item.label}
                type="button"
                role="menuitem"
                tabIndex={-1}
                aria-label={item.hint ? item.label : undefined}
                aria-describedby={item.hint ? `${id}-hint-${index}` : undefined}
                aria-disabled={item.disabled || undefined}
                onClick={() => {
                  if (item.disabled) return
                  close(true)
                  item.onSelect()
                }}
                className={cn(
                  'flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-left text-sm font-medium transition-colors focus:outline-none',
                  item.disabled
                    ? 'cursor-not-allowed text-slate-300'
                    : item.tone === 'danger'
                      ? 'text-red-700 hover:bg-red-50 focus:bg-red-50'
                      : 'text-slate-700 hover:bg-emerald-50 hover:text-emerald-800 focus:bg-emerald-50 focus:text-emerald-800',
                )}
              >
                {item.icon && (
                  <span
                    aria-hidden="true"
                    className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-slate-50 ring-1 ring-slate-100"
                  >
                    <Icon name={item.icon} className="size-4" />
                  </span>
                )}
                {item.hint ? (
                  <span className="flex min-w-0 flex-col">
                    <span>{item.label}</span>
                    <span
                      id={`${id}-hint-${index}`}
                      className={cn('text-xs font-normal', item.disabled ? 'text-slate-400' : 'text-slate-500')}
                    >
                      {item.hint}
                    </span>
                  </span>
                ) : (
                  item.label
                )}
              </button>
            ))}
            {footer && (
              <div className="mt-1 border-t border-slate-100 px-3 pt-2 pb-1.5 text-xs leading-relaxed text-slate-500">
                {footer}
              </div>
            )}
          </div>,
          document.body,
        )}
    </>
  )
}
