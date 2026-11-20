import { useEffect, useEffectEvent, useId, useRef, useState } from 'react'
import { createPortal, flushSync } from 'react-dom'
import { Icon } from '../../icons'
import { cn } from '../../utils/cn'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../icons').IconName} IconName */

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * @typedef {object} DialogProps
 * @property {boolean} open
 * @property {() => void} onClose
 * @property {ReactNode} title
 * @property {ReactNode} [description]
 * @property {IconName} [icon] A picture beside the title (drawers), e.g. the page's own icon.
 * @property {ReactNode} [children]
 * @property {ReactNode} [footer] Buttons row at the bottom (pinned under the scrolling content in the drawer variant).
 * @property {'sm' | 'md' | 'lg'} [size] Width: a modal's maximum width, or a drawer's width from `sm` up (phones always get the full screen).
 * @property {'modal' | 'drawer'} [variant] `modal` is centred; `drawer` slides in from the right (full screen on phones).
 * @property {boolean} [dismissible] Prevents closing with Escape or the backdrop while a request is running.
 */

const modalSizes = { sm: 'sm:max-w-md', md: 'sm:max-w-lg', lg: 'sm:max-w-2xl' }
const drawerSizes = { sm: 'sm:max-w-md', md: 'sm:max-w-xl', lg: 'sm:max-w-2xl' }

/** The name the closing drawer gets for its slide-out (see `::view-transition-old(app-drawer)` in index.css). */
const DRAWER_TRANSITION_NAME = 'app-drawer'

/**
 * Open dialogs, innermost last: only the top one reacts to Escape and traps Tab (e.g. a reason dialog over a drawer).
 * @type {string[]}
 */
const openDialogs = []

/**
 * Closes a drawer with a slide-out. The browser's View Transitions API keeps a picture of the drawer and slides it
 * away while the page underneath is already updated, so the dialog still leaves the DOM at once (focus, tests and
 * screen readers see no difference). Without the API, or with reduced motion, it simply closes.
 * @param {HTMLElement | null} panel
 * @param {() => void} close
 */
function closeWithSlide(panel, close) {
  const reducedMotion =
    typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (!panel || reducedMotion || typeof document.startViewTransition !== 'function') {
    close()
    return
  }
  panel.style.viewTransitionName = DRAWER_TRANSITION_NAME
  // Skipped transitions (e.g. the tab is hidden) still run the update, so the drawer always closes.
  const transition = document.startViewTransition(() => flushSync(close))
  // A drawer that stayed open (its page decided not to close it) gives the name back for the next close.
  void transition.finished.finally(() => {
    if (panel.isConnected) panel.style.viewTransitionName = ''
  })
}

/**
 * Accessible modal dialog / side drawer: focus trap, Escape to close, focus restored on close.
 * @param {DialogProps} props
 */
export function Dialog(props) {
  if (!props.open) return null
  return createPortal(<DialogPanel {...props} />, document.body)
}

/** @param {DialogProps} props */
function DialogPanel({
  onClose,
  title,
  description,
  icon,
  children,
  footer,
  size = 'md',
  variant = 'modal',
  dismissible = true,
}) {
  const panelRef = useRef(null)
  const titleId = useId()
  const descriptionId = useId()
  const isDrawer = variant === 'drawer'
  /** The drawer's content is scrolled: the header casts a shadow over it. */
  const [scrolled, setScrolled] = useState(false)

  const requestClose = () => {
    if (!dismissible) return
    if (isDrawer) closeWithSlide(panelRef.current, onClose)
    else onClose()
  }
  // Same action for the document-level key listener, which must always see the latest props.
  const onEscape = useEffectEvent(requestClose)

  useEffect(() => {
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const panel = panelRef.current
    const firstField = panel?.querySelector('[data-autofocus], input, select, textarea')
    ;(firstField ?? panel)?.focus()

    openDialogs.push(titleId)
    const { overflow } = document.body.style
    document.body.style.overflow = 'hidden'

    function onKeyDown(event) {
      if (openDialogs[openDialogs.length - 1] !== titleId) return
      if (event.key === 'Escape') {
        event.stopPropagation()
        onEscape()
        return
      }
      if (event.key !== 'Tab' || !panel) return
      const focusable = Array.from(panel.querySelectorAll(FOCUSABLE))
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (!first || !last) {
        event.preventDefault()
        return
      }
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      openDialogs.splice(openDialogs.indexOf(titleId), 1)
      document.body.style.overflow = overflow
      previouslyFocused?.focus()
    }
  }, [titleId])

  const closeButton = (
    <button
      type="button"
      onClick={requestClose}
      aria-label="Close"
      disabled={!dismissible}
      className={cn(
        'shrink-0 text-slate-500 transition-all disabled:opacity-50',
        isDrawer
          ? 'flex size-10 items-center justify-center rounded-full bg-slate-100 ring-1 ring-slate-200/80 ring-inset hover:bg-slate-200/80 hover:text-slate-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 active:scale-95'
          : '-mr-2 rounded-xl p-1.5 text-slate-400 hover:rotate-90 hover:bg-white hover:text-slate-700 hover:shadow-sm',
      )}
    >
      <Icon name="close" className="size-5" />
    </button>
  )

  const iconTile = icon && (
    <span
      aria-hidden="true"
      className="flex size-11 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-emerald-50 via-white to-teal-50 shadow-sm ring-1 ring-emerald-100"
    >
      <Icon name={icon} className="size-6" />
    </span>
  )

  return (
    <div
      className={cn(
        'fixed inset-0 z-50 flex',
        isDrawer ? 'justify-end' : 'items-end justify-center sm:items-center sm:p-4',
      )}
    >
      <div
        className={cn(
          'animate-fade-in absolute inset-0',
          isDrawer ? 'bg-slate-950/40 backdrop-blur-[3px]' : 'bg-slate-900/45 backdrop-blur-sm',
        )}
        aria-hidden="true"
        onMouseDown={requestClose}
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
        className={cn(
          'relative flex w-full flex-col overflow-hidden bg-white focus:outline-none',
          isDrawer
            ? cn(
                'animate-drawer-in h-full max-w-full shadow-[-24px_0_60px_-12px_rgb(15_23_42/0.35)] ring-1 ring-slate-900/10 sm:rounded-l-3xl',
                drawerSizes[size],
              )
            : cn(
                'animate-sheet-up max-h-[90vh] rounded-t-3xl shadow-2xl ring-1 shadow-slate-900/20 ring-slate-900/5 sm:rounded-3xl',
                modalSizes[size],
              ),
        )}
      >
        {isDrawer ? (
          // Drawer: a brand strip, picture, title and description, and a round close button; it stays put while the
          // content scrolls (with a shadow once it has scrolled).
          <header
            className={cn(
              'relative z-10 shrink-0 border-b bg-white px-5 pt-5 pb-4 transition-shadow duration-200 sm:px-6',
              scrolled ? 'border-transparent shadow-[0_8px_20px_-12px_rgb(15_23_42/0.35)]' : 'border-slate-200/70',
            )}
          >
            <span
              aria-hidden="true"
              className="absolute inset-x-0 top-0 h-1 bg-gradient-to-r from-emerald-500 via-teal-500 to-sky-500"
            />
            <div className="flex items-start gap-3">
              {iconTile}
              <div className="min-w-0 flex-1 pt-0.5">
                <h2 id={titleId} className="text-lg leading-snug font-bold tracking-tight text-slate-900">
                  {title}
                </h2>
                {description && (
                  <div id={descriptionId} className="mt-1 text-sm leading-relaxed text-slate-500">
                    {description}
                  </div>
                )}
              </div>
              {closeButton}
            </div>
          </header>
        ) : (
          <header className="flex items-start justify-between gap-4 border-b border-slate-100 bg-gradient-to-r from-emerald-50/70 via-white to-sky-50/50 px-5 py-4">
            <div className="flex min-w-0 items-start gap-3">
              {iconTile}
              <div className="min-w-0">
                <h2 id={titleId} className="text-base font-semibold text-slate-900">
                  {title}
                </h2>
                {description && (
                  <p id={descriptionId} className="mt-1 text-sm text-slate-500">
                    {description}
                  </p>
                )}
              </div>
            </div>
            {closeButton}
          </header>
        )}
        <div
          // `overscroll-contain`: scrolling to the end of the drawer never scrolls the page behind it.
          className={cn(
            'min-h-0 flex-1 overflow-y-auto',
            isDrawer ? 'overscroll-contain px-5 py-4 sm:px-6 sm:py-5' : 'px-5 py-4',
          )}
          onScroll={isDrawer ? (event) => setScrolled(event.currentTarget.scrollTop > 2) : undefined}
        >
          {children}
        </div>
        {footer && (
          <footer
            className={cn(
              'flex shrink-0 flex-wrap justify-end gap-2 border-t',
              isDrawer
                ? 'border-slate-200/70 bg-white/95 px-5 pt-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] shadow-[0_-10px_24px_-18px_rgb(15_23_42/0.3)] backdrop-blur sm:px-6'
                : 'border-slate-100 bg-slate-50/60 px-5 py-3',
            )}
          >
            {footer}
          </footer>
        )}
      </div>
    </div>
  )
}
