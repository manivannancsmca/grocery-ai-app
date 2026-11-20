import { Icon } from '../../shared/icons'
import { useAssistant } from './assistantContext'

/**
 * The "Ask AI" button of the top bar on phones. Phones have no left menu, and a floating button would cover the page
 * while it scrolls, so the assistant opens from the top bar there. From `md` the launcher is docked in the left menu
 * (AssistantProvider) and this button is hidden. Nothing shows when the signed-in user has no assistant. It is the same
 * white tile as the cart and the bell next to it; a pulsing dot shows while the assistant is answering.
 */
export function AssistantHeaderButton() {
  const { available, answering, openAssistant } = useAssistant()
  if (!available) return null
  return (
    <button
      type="button"
      aria-haspopup="dialog"
      aria-label={`Ask AI${answering ? ' (answering)' : ''}`}
      title="Ask AI"
      onClick={openAssistant}
      className="relative rounded-xl bg-white/95 p-2 text-emerald-600 shadow-sm transition-colors hover:bg-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white md:hidden print:hidden"
    >
      <Icon name="sparkle" className="size-6" />
      {answering && (
        <span
          aria-hidden="true"
          className="absolute -top-0.5 -right-0.5 size-3 animate-pulse rounded-full bg-emerald-500 ring-2 ring-chrome"
        />
      )}
    </button>
  )
}
