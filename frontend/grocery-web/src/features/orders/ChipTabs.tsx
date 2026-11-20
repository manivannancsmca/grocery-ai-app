import { type KeyboardEvent, type ReactNode, useEffect, useId, useRef } from 'react'
import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'

export interface ChipTab<T extends string> {
  id: T
  label: string
  icon?: IconName
  /** A number beside the label (decorative: the page says it in words as well, so it is not part of the name). */
  count?: number
}

interface ChipTabsProps<T extends string> {
  tabs: readonly ChipTab<T>[]
  value: T
  onChange: (id: T) => void
  /** Accessible name of the tab list. */
  label: string
  children?: ReactNode
}

/**
 * WAI-ARIA tabs drawn as a segmented bar of pills (it scrolls sideways when it does not fit, keeping the selected tab
 * in view): arrow keys / Home / End move between them and only the selected one is in the tab order. The tab's name
 * is its label; the icon and the count are decorative.
 */
export function ChipTabs<T extends string>({ tabs, value, onChange, label, children }: ChipTabsProps<T>) {
  const baseId = useId()
  const listRef = useRef<HTMLDivElement>(null)
  const tabId = (id: T) => `${baseId}-tab-${id}`
  const panelId = `${baseId}-panel`

  // When the bar scrolls, its hidden side fades out so it is clear there are more tabs (`data-fade`, set on the DOM).
  useEffect(() => {
    const list = listRef.current
    if (!list) return
    const update = () => {
      const start = list.scrollLeft > 2
      const end = list.scrollWidth - list.clientWidth - list.scrollLeft > 2
      list.dataset.fade = start && end ? 'both' : start ? 'start' : end ? 'end' : 'none'
    }
    update()
    list.addEventListener('scroll', update, { passive: true })
    const resize = typeof ResizeObserver === 'function' ? new ResizeObserver(update) : null
    resize?.observe(list)
    return () => {
      list.removeEventListener('scroll', update)
      resize?.disconnect()
    }
  }, [])

  // On a narrow screen the selected tab may be out of sight (a link to ?status=REJECTED): scroll the bar, not the page.
  // Again when its count arrives, which makes it wider.
  const selectedCount = tabs.find((tab) => tab.id === value)?.count
  useEffect(() => {
    const list = listRef.current
    const tab = list?.querySelector<HTMLElement>('[aria-selected="true"]')
    if (!list || !tab || list.scrollWidth <= list.clientWidth) return
    const left = tab.offsetLeft - (list.clientWidth - tab.offsetWidth) / 2
    list.scrollLeft = Math.max(0, left)
  }, [value, selectedCount])

  function focusTab(index: number) {
    const tab = tabs[(index + tabs.length) % tabs.length]
    if (!tab) return
    onChange(tab.id)
    document.getElementById(tabId(tab.id))?.focus()
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const current = tabs.findIndex((tab) => tab.id === value)
    if (event.key === 'ArrowRight') focusTab(current + 1)
    else if (event.key === 'ArrowLeft') focusTab(current - 1)
    else if (event.key === 'Home') focusTab(0)
    else if (event.key === 'End') focusTab(tabs.length - 1)
    else return
    event.preventDefault()
  }

  return (
    <div>
      <div
        ref={listRef}
        role="tablist"
        aria-label={label}
        onKeyDown={onKeyDown}
        className={cn(
          'relative flex w-fit max-w-full gap-1 overflow-x-auto rounded-2xl bg-white p-1 shadow-xs ring-1 ring-slate-200/80 ring-inset [scrollbar-width:none] [&::-webkit-scrollbar]:hidden',
          'data-[fade=end]:[mask-image:linear-gradient(to_right,#000_calc(100%_-_3rem),transparent)]',
          'data-[fade=start]:[mask-image:linear-gradient(to_left,#000_calc(100%_-_3rem),transparent)]',
          'data-[fade=both]:[mask-image:linear-gradient(to_right,transparent,#000_3rem,#000_calc(100%_-_3rem),transparent)]',
        )}
      >
        {tabs.map((tab) => {
          const selected = tab.id === value
          return (
            <button
              key={tab.id}
              id={tabId(tab.id)}
              type="button"
              role="tab"
              aria-selected={selected}
              aria-controls={panelId}
              tabIndex={selected ? 0 : -1}
              onClick={() => onChange(tab.id)}
              className={cn(
                'inline-flex h-9 shrink-0 items-center gap-1.5 rounded-xl pr-3 text-sm font-semibold whitespace-nowrap transition-colors',
                'focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-emerald-600',
                tab.icon ? 'pl-1.5' : 'pl-3',
                selected
                  ? 'bg-emerald-600 text-white shadow-sm shadow-emerald-600/25 focus-visible:outline-white'
                  : 'text-slate-600 hover:bg-emerald-50/70 hover:text-emerald-800',
              )}
            >
              {tab.icon && (
                <span
                  aria-hidden="true"
                  className={cn('flex size-6 items-center justify-center rounded-lg', selected ? 'bg-white' : 'bg-slate-50 ring-1 ring-slate-100 ring-inset')}
                >
                  <Icon name={tab.icon} className="size-4" />
                </span>
              )}
              {tab.label}
              {tab.count !== undefined && (
                <span
                  aria-hidden="true"
                  className={cn(
                    'min-w-5 rounded-full px-1.5 text-center text-xs leading-5 font-bold tabular-nums',
                    selected ? 'bg-white/20 text-white' : 'bg-slate-100 text-slate-500',
                  )}
                >
                  {tab.count.toLocaleString('en-IN')}
                </span>
              )}
            </button>
          )
        })}
      </div>
      <div id={panelId} role="tabpanel" aria-labelledby={tabId(value)} className="pt-5">
        {children}
      </div>
    </div>
  )
}
