import { lazy, type ReactNode, Suspense, useCallback, useEffect, useMemo, useState } from 'react'
import { useLocation } from 'react-router'
import { useAuth } from '../../shared/auth/useAuth'
import { Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { type AgentView, pickAgent } from './api'
import { AssistantContext, type AssistantContextValue } from './assistantContext'
import { useAgents } from './hooks'

/** The panel, the chat stream and the approval cards: downloaded on first use only. */
const AssistantSession = lazy(() => import('./AssistantSession'))

interface AssistantRootProps {
  agent: AgentView
  admin: boolean
  /** Phase 12: the other agents the server offers (a delivery manager has `admin-bi` and `delivery`). */
  otherAgents: readonly AgentView[]
  onSwitchAgent: (agentId: string) => void
  open: boolean
  onOpen: () => void
  onClose: () => void
  /** The assistant is writing an answer (shared with the phone header's button through the context). */
  streaming: boolean
  onStreamingChange: (streaming: boolean) => void
  dock?: AssistantDock
}

/**
 * Where the launcher sits from the `md` breakpoint up: docked at the bottom of the left menu — full width when the
 * menu is expanded (`sidebar`), an icon when it is collapsed (`rail`) — so it never covers page content. Without a
 * dock it floats at the bottom right. Phones have no floating launcher at all: the top bar's "Ask AI" button
 * (`AssistantHeaderButton`) opens the assistant there, so nothing ever covers the page while it scrolls.
 */
export type AssistantDock = 'sidebar' | 'rail'

/** The room the docked launcher needs at the bottom of the left menu: its height, its gap to the edge and a margin. */
const ASSISTANT_SPACE = '4.5rem'

/** One user's assistant: the launcher, and the session once the panel has been opened. */
function AssistantRoot({ agent, admin, otherAgents, onSwitchAgent, open, onOpen, onClose, streaming, onStreamingChange, dock }: AssistantRootProps) {
  const [started, setStarted] = useState(open)
  if (open && !started) setStarted(true)

  // A launcher that goes away (sign-out, another agent) is not answering any more.
  useEffect(() => () => onStreamingChange(false), [onStreamingChange])

  // While the launcher shows, the left menu keeps this much room at the bottom of its list, so the docked button never
  // covers a menu item.
  useEffect(() => {
    const root = document.documentElement
    root.style.setProperty('--assistant-space', ASSISTANT_SPACE)
    return () => {
      root.style.removeProperty('--assistant-space')
    }
  }, [])

  return (
    <>
      <button
        type="button"
        aria-haspopup="dialog"
        aria-label={`Open the assistant${streaming ? ' (answering)' : ''}`}
        title={dock === 'rail' ? 'Ask AI' : undefined}
        onClick={onOpen}
        className={cn(
          // Hidden on phones (the top bar's "Ask AI" button stands in); from `md` docked in the left menu (AppLayout's
          // sidebar is w-64 or w-20 with px-3), or floating at the bottom right.
          'fixed right-6 z-30 hidden items-center justify-center gap-2 rounded-full bg-emerald-600 px-4 py-3 text-sm font-semibold text-white shadow-lg ring-4 ring-white/70 transition-[bottom,width] duration-300 print:hidden! hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 motion-reduce:transition-none md:flex',
          dock
            ? 'md:right-auto md:bottom-4 md:left-3 md:rounded-xl md:bg-gradient-to-r md:from-emerald-500 md:to-teal-500 md:shadow-black/25 md:ring-1 md:ring-white/20 md:hover:brightness-110'
            : 'md:bottom-6',
          dock === 'sidebar' && 'md:w-58',
          dock === 'rail' && 'md:w-14 md:px-0',
        )}
      >
        <Icon name="sparkle" className="size-5 shrink-0" />
        <span className={cn(dock === 'rail' && 'md:hidden')}>Ask AI</span>
        {streaming && <span aria-hidden="true" className="size-2 animate-pulse rounded-full bg-white" />}
      </button>
      {started && (
        <Suspense
          fallback={
            open ? (
              <div
                className={cn(
                  // Phones: under the top bar, where the "Ask AI" button was pressed.
                  'fixed top-20 right-4 z-30 rounded-full bg-white p-2 shadow-lg md:top-auto md:right-6 md:bottom-24',
                  dock && 'md:right-auto md:bottom-20 md:left-5',
                )}
              >
                <Spinner label="Opening the assistant" />
              </div>
            ) : null
          }
        >
          <AssistantSession
            agent={agent}
            admin={admin}
            otherAgents={otherAgents}
            onSwitchAgent={onSwitchAgent}
            open={open}
            onClose={onClose}
            onStreamingChange={onStreamingChange}
          />
        </Suspense>
      )}
    </>
  )
}

/**
 * Phase 11: the assistant on every page for customers (`shopping`) and admins (`admin-bi`); Phase 12 adds vendors
 * (`vendor`), restaurant owners (`restaurant`) and delivery partners and managers (`delivery`). Which agent comes
 * from `GET /api/ai/agents`; when the server offers none (or cannot be asked), there is no launcher and nothing
 * else changes. With more than one agent the panel offers a switch. The panel closes on navigation (e.g. following
 * the link of a placed order).
 */
export function AssistantProvider({ children, dock }: { children: ReactNode; dock?: AssistantDock }) {
  const { status, area, user } = useAuth()
  const userId = status === 'authenticated' ? (user?.id ?? null) : null
  const eligible = userId !== null && area !== null
  const agents = useAgents(eligible)
  const [chosen, setChosen] = useState<{ userId: string; agentId: string } | null>(null)
  const chosenAgentId = chosen !== null && chosen.userId === userId ? chosen.agentId : null
  const agent = eligible ? pickAgent(agents.data, area, { roles: user?.roles, chosen: chosenAgentId }) : null
  const otherAgents = useMemo(() => (agent ? (agents.data ?? []).filter((candidate) => candidate.id !== agent.id) : []), [agent, agents.data])
  const switchAgent = useCallback(
    (agentId: string) => {
      if (userId !== null) setChosen({ userId, agentId })
    },
    [userId],
  )

  const [openFor, setOpenFor] = useState<string | null>(null)
  const { pathname } = useLocation()
  const [lastPath, setLastPath] = useState(pathname)
  if (pathname !== lastPath) {
    setLastPath(pathname)
    if (openFor !== null) setOpenFor(null)
  }

  const open = agent !== null && userId !== null && openFor === userId
  const openAssistant = useCallback(() => {
    if (userId !== null) setOpenFor(userId)
  }, [userId])
  const close = useCallback(() => setOpenFor(null), [])
  const [answering, setAnswering] = useState(false)

  const value = useMemo<AssistantContextValue>(
    () => ({ available: agent !== null, answering: agent !== null && answering, openAssistant }),
    [agent, answering, openAssistant],
  )

  return (
    <AssistantContext value={value}>
      {children}
      {agent && userId && (
        <AssistantRoot
          key={`${userId}:${agent.id}`}
          agent={agent}
          admin={area === 'ADMIN'}
          otherAgents={otherAgents}
          onSwitchAgent={switchAgent}
          open={open}
          onOpen={openAssistant}
          onClose={close}
          streaming={answering}
          onStreamingChange={setAnswering}
          dock={dock}
        />
      )}
    </AssistantContext>
  )
}
