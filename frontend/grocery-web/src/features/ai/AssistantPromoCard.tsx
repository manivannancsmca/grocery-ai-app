import { Link } from 'react-router'
import { Icon } from '../../shared/icons'
import { AssistantIllustration } from '../../shared/illustrations'
import { Button } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { useAssistant } from './assistantContext'

/** Storefront: what the shopping assistant does, with a way to open it (or to sign in for it). */
export function AssistantPromoCard({ signedIn, className }: { signedIn: boolean; className?: string }) {
  const { available, openAssistant } = useAssistant()
  return (
    <div
      className={cn(
        'relative flex overflow-hidden rounded-2xl border border-violet-100 bg-gradient-to-br from-violet-50 via-white to-emerald-50 p-5 shadow-sm sm:p-6',
        className,
      )}
    >
      <div aria-hidden="true" className="pointer-events-none absolute -top-16 -right-10 size-48 rounded-full bg-violet-200/40 blur-2xl" />
      <div className="relative flex min-w-0 flex-1 flex-col">
        <p className="flex items-center gap-1.5 text-xs font-semibold tracking-wider text-violet-700 uppercase">
          <Icon name="sparkle" className="size-4" />
          Shop smarter
        </p>
        <h2 className="mt-1.5 text-lg font-semibold text-slate-900">
          AI shopping assistant
        </h2>
        <p className="mt-1.5 max-w-sm text-sm leading-relaxed text-slate-600">
          Ask for products, fill your cart and check your orders in your own words. It never orders on its own: you confirm every
          purchase.
        </p>
        <div className="mt-auto pt-4">
          {available ? (
            <Button onClick={openAssistant}>
              <Icon name="sparkle" className="size-4" />
              Ask the assistant
            </Button>
          ) : !signedIn ? (
            <Link
              to="/login"
              className="inline-flex h-10 items-center rounded-xl bg-white px-4 text-sm font-semibold text-emerald-700 shadow-xs ring-1 ring-emerald-200 ring-inset hover:bg-emerald-50 hover:text-emerald-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
            >
              Sign in to use it →
            </Link>
          ) : null}
        </div>
      </div>
      <div className="relative ml-3 hidden w-28 shrink-0 self-center sm:block xl:w-32">
        <AssistantIllustration className="animate-float" />
      </div>
    </div>
  )
}
