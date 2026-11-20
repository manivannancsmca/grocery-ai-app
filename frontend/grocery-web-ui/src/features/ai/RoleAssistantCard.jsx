import { Link } from 'react-router'
import { Button, FeatureCard } from '../../shared/components/ui'
import { useAssistant } from './assistantContext'

/**
 * @typedef {object} RoleAssistantCardProps
 * @property {string} title
 * @property {string} description
 * @property {string} actionsPath The role's approvals page (`/vendor/assistant`, …).
 */

/**
 * Phase 12 dashboards: what the role's assistant does, a way to open it, and the link to its actions.
 * @param {RoleAssistantCardProps} props
 */
export function RoleAssistantCard({ title, description, actionsPath }) {
  const { available, openAssistant } = useAssistant()
  return (
    <FeatureCard title={title} icon="sparkle" accent="violet">
      <p className="text-sm text-slate-500">{description}</p>
      <div className="mt-auto flex flex-wrap items-center gap-3 pt-3">
        {available && (
          <Button size="sm" variant="secondary" onClick={openAssistant}>
            Ask the assistant
          </Button>
        )}
        <Link
          to={actionsPath}
          className="text-sm font-semibold text-emerald-700 transition-colors hover:text-emerald-900"
        >
          Assistant actions →
        </Link>
      </div>
    </FeatureCard>
  )
}
