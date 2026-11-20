import { Link } from 'react-router'
import { Button } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { useAssistant } from '../ai/assistantContext'

/**
 * The delivery assistant on the partner's dashboard, as one slim card: what it helps with, a button that opens it
 * (when the signed-in user has it) and the link to its actions (`/delivery/assistant`).
 */
export function PartnerAssistantCard() {
  const { available, openAssistant } = useAssistant()
  return (
    <section
      aria-labelledby="partner-assistant-title"
      className="relative isolate flex flex-wrap items-center gap-x-4 gap-y-3 overflow-hidden rounded-2xl border border-slate-200/70 bg-white px-3.5 py-3 shadow-sm shadow-slate-900/[0.04]"
    >
      <span aria-hidden="true" className="absolute inset-y-0 left-0 w-1 bg-gradient-to-b from-violet-400 to-fuchsia-500" />
      <span aria-hidden="true" className="absolute -top-10 -right-10 -z-10 size-28 rounded-full bg-violet-200/40 blur-2xl" />
      <div className="flex min-w-0 flex-1 basis-64 items-center gap-3">
        <span aria-hidden="true" className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-violet-50 to-fuchsia-50 ring-1 ring-violet-100">
          <Icon name="sparkle" className="size-5" />
        </span>
        <div className="min-w-0">
          <h2 id="partner-assistant-title" className="text-sm font-semibold text-slate-900">
            AI delivery assistant
          </h2>
          <p className="text-xs text-slate-500 sm:text-sm">Ask about your active delivery, open offers, your history or your summary.</p>
        </div>
      </div>
      <div className="flex shrink-0 flex-wrap items-center gap-3">
        {available && (
          <Button size="sm" variant="secondary" onClick={openAssistant}>
            Ask the assistant
          </Button>
        )}
        <Link
          to="/delivery/assistant"
          className="rounded text-sm font-semibold text-emerald-700 transition-colors hover:text-emerald-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
        >
          Assistant actions →
        </Link>
      </div>
    </section>
  )
}
