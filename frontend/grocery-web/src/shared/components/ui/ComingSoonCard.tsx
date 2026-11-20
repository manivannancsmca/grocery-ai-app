interface ComingSoonCardProps {
  title: string
  description: string
  phase: number
}

/** Placeholder for a feature scheduled in a later implementation phase. */
export function ComingSoonCard({ title, description, phase }: ComingSoonCardProps) {
  return (
    <div className="rounded-2xl border border-dashed border-slate-300 bg-gradient-to-br from-white to-slate-50 p-5 shadow-sm">
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-sm font-semibold text-slate-900">{title}</h3>
        <span className="shrink-0 rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">
          Phase {phase}
        </span>
      </div>
      <p className="mt-2 text-sm text-slate-500">{description}</p>
    </div>
  )
}
