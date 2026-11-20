import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { cn } from '../../utils/cn'
import { placeTooltip } from './placeTooltip'

const TOOLTIP_ID = 'app-tooltip'
/** Hovering this long shows the tooltip; moving straight on to another element shows its tooltip at once. */
const SHOW_DELAY_MS = 450
const SKIP_DELAY_MS = 300
const TARGETS = '[title]:not([title=""]), [data-tooltip]:not([data-tooltip=""])'

/** Only devices with a mouse: touch screens never showed `title` tooltips. (Tests have no `matchMedia`: off there.) */
function canHover(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(hover: hover) and (pointer: fine)').matches
}

const targetOf = (node: EventTarget | null): Element | null => (node instanceof Element ? node.closest(TARGETS) : null)
const textOf = (element: Element) => element.getAttribute('data-tooltip') ?? element.getAttribute('data-tooltip-title') ?? ''

/** Whether the element has a name of its own, so its title only describes it (an icon button's title is its name). */
function hasOwnName(element: Element): boolean {
  if (element.hasAttribute('aria-label') || element.hasAttribute('aria-labelledby')) return true
  if (element instanceof HTMLImageElement && element.alt) return true
  return (element.textContent ?? '').trim() !== ''
}

function describeBy(element: Element): () => void {
  const before = element.getAttribute('aria-describedby')
  element.setAttribute('aria-describedby', [before, TOOLTIP_ID].filter(Boolean).join(' '))
  return () => (before === null ? element.removeAttribute('aria-describedby') : element.setAttribute('aria-describedby', before))
}

/**
 * Moves the element's `title` aside so the browser does not show its own tooltip, keeping what assistive technology
 * gets from it: the title becomes the `aria-label` of an element without a name, or the tooltip describes the element
 * (`aria-describedby`). Returns the undo.
 */
function takeTitle(element: Element): () => void {
  const title = element.getAttribute('title')
  if (title === null) return describeBy(element) // a data-tooltip element: nothing to hide
  const named = hasOwnName(element)
  element.setAttribute('data-tooltip-title', title)
  element.removeAttribute('title')
  if (!named) element.setAttribute('aria-label', title)
  const undoDescription = named ? describeBy(element) : () => {}
  return () => {
    undoDescription()
    if (!named) element.removeAttribute('aria-label')
    const latest = element.getAttribute('data-tooltip-title')
    element.removeAttribute('data-tooltip-title')
    // React may have set a new title meanwhile: keep that one.
    if (!element.hasAttribute('title') && latest !== null) element.setAttribute('title', latest)
  }
}

interface Shown {
  key: number
  text: string
  target: DOMRect
}

/**
 * App-wide tooltips: shows a styled tooltip instead of the browser's default one for every element with a `title`
 * (or a `data-tooltip`), across the whole app and third-party widgets such as the map, without changing components.
 * It appears after a short hover above the element (below it near the top of the screen) and on keyboard focus;
 * leaving, a click, Escape or scrolling hide it. Mounted once, in `AppProviders`.
 */
export function TooltipLayer() {
  const [shown, setShown] = useState<Shown | null>(null)
  const tooltipRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!canHover()) return
    let active: { element: Element; undo: () => void; observer: MutationObserver } | null = null
    let showTimer: number | undefined
    let watchTimer: number | undefined
    let hiddenAt = 0
    let keyboard = false
    let count = 0

    function hide() {
      window.clearTimeout(showTimer)
      window.clearInterval(watchTimer)
      if (active) {
        active.observer.disconnect()
        active.undo()
        active = null
        hiddenAt = Date.now()
      }
      setShown(null)
    }

    function show(element: Element) {
      if (!element.isConnected || !textOf(element)) return
      count += 1
      setShown({ key: count, text: textOf(element), target: element.getBoundingClientRect() })
      // An element removed while its tooltip is up (a row that went away) takes the tooltip with it.
      watchTimer = window.setInterval(() => {
        if (!element.isConnected) hide()
      }, 300)
    }

    function activate(element: Element, now: boolean) {
      if (active?.element === element) return
      hide()
      const undo = takeTitle(element)
      // A title or data-tooltip changed while the tooltip is up (e.g. a live timer): follow it.
      const observer = new MutationObserver(() => {
        const title = element.getAttribute('title')
        if (title !== null) {
          element.setAttribute('data-tooltip-title', title)
          element.removeAttribute('title')
        }
        setShown((current) => (current ? { ...current, text: textOf(element) } : current))
      })
      observer.observe(element, { attributes: true, attributeFilter: ['title', 'data-tooltip'] })
      active = { element, undo, observer }
      if (now || Date.now() - hiddenAt < SKIP_DELAY_MS) show(element)
      else showTimer = window.setTimeout(() => show(element), SHOW_DELAY_MS)
    }

    function onMouseOver(event: MouseEvent) {
      const element = targetOf(event.target)
      if (element) activate(element, false)
      else if (active && !active.element.contains(event.target as Node)) hide()
    }
    function onMouseOut(event: MouseEvent) {
      if (active && !active.element.contains(event.relatedTarget as Node | null)) hide()
    }
    function onFocusIn(event: FocusEvent) {
      const element = keyboard ? targetOf(event.target) : null
      if (element) activate(element, false)
    }
    function onFocusOut(event: FocusEvent) {
      if (active && active.element.contains(event.target as Node)) hide()
    }
    function onKeyDown(event: KeyboardEvent) {
      keyboard = true
      if (event.key === 'Escape') hide()
    }
    function onPointerDown() {
      keyboard = false
      hide()
    }

    const passive = { capture: true, passive: true }
    document.addEventListener('mouseover', onMouseOver, true)
    document.addEventListener('mouseout', onMouseOut, true)
    document.addEventListener('focusin', onFocusIn, true)
    document.addEventListener('focusout', onFocusOut, true)
    document.addEventListener('keydown', onKeyDown, true)
    document.addEventListener('pointerdown', onPointerDown, true)
    window.addEventListener('scroll', hide, passive)
    window.addEventListener('resize', hide, passive)
    return () => {
      hide()
      document.removeEventListener('mouseover', onMouseOver, true)
      document.removeEventListener('mouseout', onMouseOut, true)
      document.removeEventListener('focusin', onFocusIn, true)
      document.removeEventListener('focusout', onFocusOut, true)
      document.removeEventListener('keydown', onKeyDown, true)
      document.removeEventListener('pointerdown', onPointerDown, true)
      window.removeEventListener('scroll', hide, passive)
      window.removeEventListener('resize', hide, passive)
    }
  }, [])

  // Measure the tooltip, then place it on the DOM directly (it renders hidden until then).
  useLayoutEffect(() => {
    const tooltip = tooltipRef.current
    if (!shown || !tooltip) return
    const place = placeTooltip(shown.target, tooltip.getBoundingClientRect(), { width: window.innerWidth, height: window.innerHeight })
    tooltip.style.top = `${place.top}px`
    tooltip.style.left = `${place.left}px`
    tooltip.style.setProperty("--arrow-left", `${place.arrowLeft - 4}px`)
    tooltip.dataset.side = place.side
  }, [shown])

  if (!shown) return null
  return createPortal(
    <div
      key={shown.key}
      ref={tooltipRef}
      id={TOOLTIP_ID}
      role="tooltip"
      className={cn(
        "group/tooltip pointer-events-none invisible fixed top-0 left-0 z-[70] max-w-[min(20rem,calc(100vw-16px))] rounded-lg bg-slate-900 px-2.5 py-1.5 text-xs leading-snug font-medium break-words whitespace-pre-line text-white shadow-lg shadow-slate-900/25 ring-1 ring-white/10",
        "data-side:visible data-side:animate-fade-in",
      )}
    >
      {shown.text}
      <span
        aria-hidden="true"
        className="absolute left-(--arrow-left) size-2 rotate-45 bg-slate-900 group-data-[side=bottom]/tooltip:-top-1 group-data-[side=top]/tooltip:-bottom-1"
      />
    </div>,
    document.body,
  )
}
