import { useId } from 'react'
import { type RoleArea, ROLE_LABELS } from '../../shared/auth/roles'
import { Icon } from '../../shared/icons'
import { DEV_ACCOUNTS, type DevAccount } from './devAccounts'
import { STAKEHOLDERS } from './stakeholders'

interface DevAccountsPanelProps {
  /** The stakeholder tab that is open: its accounts are listed. */
  area: RoleArea
  /** Called with the account to put into the sign-in form. */
  onUse: (account: DevAccount) => void
}

/**
 * Development only: the seeded sign-ins of the selected stakeholder, each one click away from the form.
 * Loaded lazily by `LoginPage` and only on the Vite dev server.
 */
export default function DevAccountsPanel({ area, onUse }: DevAccountsPanelProps) {
  const headingId = useId()
  const accounts = DEV_ACCOUNTS[area]

  return (
    <section
      aria-labelledby={headingId}
      className="mt-6 rounded-2xl border border-dashed border-amber-300/90 bg-gradient-to-br from-amber-50 to-orange-50/50 p-3.5"
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="rounded-md bg-amber-200/80 px-1.5 py-0.5 text-[10px] font-bold tracking-wider text-amber-900 uppercase">
          Dev only
        </span>
        <h2 id={headingId} className="text-sm font-semibold text-slate-800">
          {STAKEHOLDERS[area].label} dev {accounts.length === 1 ? 'account' : 'accounts'}
        </h2>
        <p className="w-full text-xs text-slate-500">Pick an account to fill in the form, then sign in.</p>
      </div>
      <ul className="mt-2.5 space-y-1.5">
        {accounts.map((account) => (
          <li key={account.email}>
            <button
              type="button"
              onClick={() => onUse(account)}
              className="group flex w-full items-center gap-3 rounded-xl bg-white/90 px-3 py-2 text-left ring-1 ring-amber-200/80 transition-all duration-200 hover:bg-white hover:shadow-sm hover:ring-amber-400 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-600"
            >
              <span aria-hidden="true" className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-amber-100/80">
                <Icon name={STAKEHOLDERS[area].icon} className="size-5" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-xs font-semibold text-slate-900">{ROLE_LABELS[account.role]}</span>
                <span className="block truncate font-mono text-[11px] text-slate-500">{account.email}</span>
                <span className="block font-mono text-[11px] text-slate-500">{account.password}</span>
              </span>
              <span className="inline-flex shrink-0 items-center gap-1 rounded-lg bg-amber-100 px-2.5 py-1 text-xs font-semibold text-amber-900 transition-colors group-hover:bg-amber-200">
                Use
                <Icon name="arrowRight" className="size-3.5" />
              </span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}
