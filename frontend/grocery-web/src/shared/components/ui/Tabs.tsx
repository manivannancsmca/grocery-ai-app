import { type KeyboardEvent, type ReactNode, useId } from 'react'
import { cn } from '../../utils/cn'

export interface TabItem<T extends string> {
  id: T
  label: ReactNode
}

interface TabsProps<T extends string> {
  tabs: readonly TabItem<T>[]
  value: T
  onChange: (id: T) => void
  /** Accessible name of the tab list. */
  label: string
  /** Content of the selected tab. */
  children?: ReactNode
}

/** WAI-ARIA tabs: arrow keys / Home / End move between tabs, only the active tab is in the tab order. */
export function Tabs<T extends string>({ tabs, value, onChange, label, children }: TabsProps<T>) {
  const baseId = useId()

  const tabId = (id: T) => `${baseId}-tab-${id}`
  const panelId = `${baseId}-panel`

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
        role="tablist"
        aria-label={label}
        onKeyDown={onKeyDown}
        className="flex w-fit max-w-full gap-1 overflow-x-auto rounded-2xl bg-slate-100/80 p-1 ring-1 ring-slate-200/60 ring-inset"
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
                'inline-flex shrink-0 items-center gap-2 rounded-xl px-3.5 py-2 text-sm font-semibold whitespace-nowrap transition-all duration-200',
                'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-emerald-600',
                selected
                  ? 'bg-white text-emerald-700 shadow-sm shadow-slate-900/10 ring-1 ring-slate-200/80'
                  : 'text-slate-500 hover:bg-white/60 hover:text-slate-800',
              )}
            >
              {tab.label}
            </button>
          )
        })}
      </div>
      <div id={panelId} role="tabpanel" aria-labelledby={tabId(value)} className="animate-fade-in pt-4">
        {children}
      </div>
    </div>
  )
}
