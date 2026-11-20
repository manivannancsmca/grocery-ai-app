import { Spinner } from '../../shared/components/ui'
import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import type { ToolActivity } from './api'
import { toolStatusLabel } from './labels'

const CHIP_CLASSES: Record<string, string> = {
  STARTED: 'bg-slate-50 text-slate-600 ring-slate-200',
  SUCCEEDED: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  FAILED: 'bg-red-50 text-red-700 ring-red-300',
  BLOCKED: 'bg-red-50 text-red-800 ring-red-400 ring-2 font-semibold',
  APPROVAL_REQUIRED: 'bg-amber-50 text-amber-800 ring-amber-300',
}

/** Done, waiting for approval, blocked; anything else (FAILED) is a red cross. */
const STATUS_ICONS: Record<string, IconName> = {
  SUCCEEDED: 'checkCircle',
  APPROVAL_REQUIRED: 'shield',
  BLOCKED: 'ban',
}

function StatusMark({ status }: { status: string }) {
  if (status === 'STARTED') return <Spinner size="sm" />
  return <Icon name={STATUS_ICONS[status] ?? 'xCircle'} className="size-3.5" />
}

/**
 * The tools the assistant used for one answer, as chips: label and state. A blocked or failed call is styled as
 * such and its reason is shown under the chips (tool summaries are the server's words, shown as text).
 */
export function ToolChips({ tools }: { tools: readonly ToolActivity[] }) {
  if (tools.length === 0) return null
  const problems = tools.filter((tool) => (tool.status === 'BLOCKED' || tool.status === 'FAILED') && tool.summary)
  return (
    <div className="space-y-1">
      <ul aria-label="Tools used" className="flex flex-wrap gap-1.5">
        {tools.map((tool) => (
          <li
            key={tool.invocationId}
            data-status={tool.status}
            title={tool.summary ?? undefined}
            className={cn(
              'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs ring-1 ring-inset',
              CHIP_CLASSES[tool.status] ?? CHIP_CLASSES.STARTED,
            )}
          >
            <StatusMark status={tool.status} />
            <span>{tool.label}</span>
            {tool.status === 'STARTED' || tool.status === 'SUCCEEDED' ? (
              <span className="sr-only"> ({toolStatusLabel(tool.status)})</span>
            ) : (
              <span> · {toolStatusLabel(tool.status)}</span>
            )}
          </li>
        ))}
      </ul>
      {problems.map((tool) => (
        <p key={tool.invocationId} className={cn('text-xs', tool.status === 'BLOCKED' ? 'text-red-800' : 'text-red-700')}>
          {tool.label}: {tool.summary}
        </p>
      ))}
    </div>
  )
}
