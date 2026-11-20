import { type KeyboardEvent, useRef } from 'react'
import type { RoleArea } from '../../shared/auth/roles'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { BrandPanel, BrandPitch } from './AuthShell'
import { StakeholderArt } from './StakeholderArt'
import { STAKEHOLDER_LIST, STAKEHOLDERS } from './stakeholders'

/**
 * The sign-in screen's "who are you" choice is split in two: the tabs sit above the form, the panel they control
 * is the brand side's pitch. Both take the same `baseId` so the ARIA ids match.
 */
const tabId = (baseId: string, area: RoleArea) => `${baseId}-tab-${area}`
const panelId = (baseId: string) => `${baseId}-panel`

interface StakeholderTabsProps {
  baseId: string
  selected: RoleArea
  onSelect: (area: RoleArea) => void
  className?: string
}

/**
 * One tab per kind of FreshCart user, as a segmented control. The choice only tailors the screen; the account
 * decides where sign-in leads.
 */
export function StakeholderTabs({ baseId, selected, onSelect, className }: StakeholderTabsProps) {
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([])
  const captionId = `${baseId}-caption`

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const last = STAKEHOLDER_LIST.length - 1
    const next =
      event.key === 'ArrowRight' ? (index === last ? 0 : index + 1)
      : event.key === 'ArrowLeft' ? (index === 0 ? last : index - 1)
      : event.key === 'Home' ? 0
      : event.key === 'End' ? last
      : null
    const target = next === null ? undefined : STAKEHOLDER_LIST[next]
    if (next === null || !target) return
    event.preventDefault()
    onSelect(target.id)
    tabRefs.current[next]?.focus()
  }

  return (
    <div className={className}>
      <p id={captionId} className="mb-2 text-xs font-semibold tracking-wide text-slate-500 uppercase">
        How do you use FreshCart?
      </p>
      <div role="tablist" aria-labelledby={captionId} className="grid grid-cols-5 gap-1 rounded-2xl bg-slate-100 p-1 ring-1 ring-slate-200/60">
        {STAKEHOLDER_LIST.map((stakeholder, index) => {
          const isSelected = stakeholder.id === selected
          return (
            <button
              key={stakeholder.id}
              ref={(element) => {
                tabRefs.current[index] = element
              }}
              type="button"
              role="tab"
              id={tabId(baseId, stakeholder.id)}
              aria-selected={isSelected}
              aria-controls={panelId(baseId)}
              tabIndex={isSelected ? 0 : -1}
              onClick={() => onSelect(stakeholder.id)}
              onKeyDown={(event) => onKeyDown(event, index)}
              className={cn(
                // Five tabs share a phone's width: the label is a little smaller there so "Restaurant" fits.
                'group flex min-w-0 flex-col items-center gap-1 rounded-xl px-0.5 py-2 text-[10.5px] leading-tight font-semibold transition-all duration-200',
                'sm:text-xs focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-emerald-600',
                isSelected
                  ? 'bg-white text-emerald-800 shadow-sm ring-1 ring-slate-900/5'
                  : 'text-slate-500 hover:bg-white/60 hover:text-slate-800',
              )}
            >
              <Icon
                name={stakeholder.icon}
                className={cn(
                  'size-6 transition-all duration-200',
                  isSelected ? 'scale-110' : 'opacity-75 grayscale-[40%] group-hover:opacity-100 group-hover:grayscale-0',
                )}
              />
              <span className="max-w-full truncate">{stakeholder.label}</span>
            </button>
          )
        })}
      </div>
    </div>
  )
}

interface StakeholderShowcaseProps {
  baseId: string
  selected: RoleArea
}

/** Brand panel of the sign-in screen: what the chosen kind of user finds after signing in (the tabs' panel). */
export function StakeholderShowcase({ baseId, selected }: StakeholderShowcaseProps) {
  const active = STAKEHOLDERS[selected]
  return (
    <BrandPanel
      label="Who FreshCart is for"
      eyebrow="One sign-in for everyone"
      footnote={
        <>
          <Icon name="lock" className="size-4" />
          Whichever you pick, your account opens its own workspace.
        </>
      }
    >
      <div key={selected} role="tabpanel" id={panelId(baseId)} aria-labelledby={tabId(baseId, selected)} className="animate-fade-in">
        <BrandPitch
          headline={active.headline}
          summary={active.summary}
          items={active.points}
          art={<StakeholderArt area={selected} />}
        />
      </div>
    </BrandPanel>
  )
}
