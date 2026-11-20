import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import type { AgentView } from './api'

/** Short labels and pictures for the switcher; an unknown agent shows its full name and the sparkle. */
const AGENT_TABS: Record<string, { label: string; icon: IconName }> = {
  shopping: { label: 'Shopping', icon: 'cart' },
  payment: { label: 'Payments', icon: 'card' },
  support: { label: 'Support', icon: 'chat' },
  'admin-bi': { label: 'Business', icon: 'chart' },
  delivery: { label: 'Delivery', icon: 'scooter' },
  vendor: { label: 'Store', icon: 'store' },
  restaurant: { label: 'Restaurant', icon: 'cloche' },
}

/** The same order whichever assistant is open, so the buttons never jump around. */
const ORDER = Object.keys(AGENT_TABS)

function rank(agentId: string): number {
  const index = ORDER.indexOf(agentId)
  return index === -1 ? ORDER.length : index
}

interface AgentSwitcherProps {
  current: AgentView
  /** The other assistants the server offers this user. */
  others: readonly AgentView[]
  /** While an answer streams, switching waits. */
  disabled?: boolean
  onSwitch: (agentId: string) => void
}

/**
 * A segmented control to switch between the user's assistants (a customer has Shopping, Payments and Support; a
 * delivery manager has Business and Delivery). Each button's accessible name is "Switch to <assistant>" and its
 * tooltip says what that assistant does.
 */
export function AgentSwitcher({ current, others, disabled = false, onSwitch }: AgentSwitcherProps) {
  const agents = [current, ...others].sort((a, b) => rank(a.id) - rank(b.id))
  return (
    <div role="group" aria-label="Assistants" className="flex gap-1 rounded-2xl bg-slate-100 p-1 ring-1 ring-slate-200/70 ring-inset">
      {agents.map((agent) => {
        const active = agent.id === current.id
        const tab = AGENT_TABS[agent.id] ?? { label: agent.name, icon: 'sparkle' }
        return (
          <button
            key={agent.id}
            type="button"
            aria-pressed={active}
            aria-label={active ? agent.name : `Switch to ${agent.name}`}
            title={agent.description}
            disabled={disabled && !active}
            onClick={() => {
              if (!active) onSwitch(agent.id)
            }}
            className={cn(
              'flex min-h-10 min-w-0 flex-1 items-center justify-center gap-1.5 rounded-xl px-2 text-sm font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 disabled:opacity-50',
              active ? 'bg-white text-emerald-800 shadow-sm ring-1 ring-slate-200' : 'text-slate-600 hover:bg-white/70 hover:text-slate-900',
            )}
          >
            <Icon name={tab.icon} className="size-4 shrink-0" />
            <span className="truncate">{tab.label}</span>
          </button>
        )
      })}
    </div>
  )
}
