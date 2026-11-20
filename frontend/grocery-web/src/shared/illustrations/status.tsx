import { Backdrop, GroundShadow, IllustrationSvg, type IllustrationProps, Sparkle } from './Illustration'

/** A signpost pointing two ways with a question mark — page not found. */
export function LostIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={120} cy={90} r={76} tone="amber" />
      <GroundShadow cx={120} cy={160} rx={70} />
      <rect x="114" y="40" width="12" height="120" rx="4" fill="#a16207" />
      <path d="M126 50h62l14 14-14 14h-62Z" fill="#10b981" />
      <path d="M114 88H52l-14 14 14 14h62Z" fill="#fbbf24" />
      <path d="M140 64h36M62 102h36" stroke="#fff" strokeOpacity="0.8" strokeWidth="5" strokeLinecap="round" />
      <path d="M48 150c4-14 14-20 24-18-2 10-12 16-24 18ZM190 152c-2-14 6-24 18-26 0 12-8 22-18 26Z" fill="#22c55e" />
      <circle cx="120" cy="22" r="14" fill="#fff" stroke="#e2e8f0" strokeWidth="2" />
      <path d="M115 18a5 5 0 1 1 7 4.5c-1.5.5-2 2-2 3.5" stroke="#f59e0b" strokeWidth="3" fill="none" strokeLinecap="round" />
      <circle cx="120" cy="30" r="1.8" fill="#f59e0b" />
      <Sparkle x={210} y={40} size={6} fill="#34d399" />
    </IllustrationSvg>
  )
}

/** An unplugged cable with a spark — something went wrong. */
export function ErrorIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={120} cy={90} r={76} tone="rose" />
      <GroundShadow cx={120} cy={160} rx={70} />
      <path d="M20 124c30 0 40-30 64-30" stroke="#475569" strokeWidth="7" fill="none" strokeLinecap="round" />
      <rect x="82" y="80" width="26" height="28" rx="6" fill="#64748b" />
      <path d="M108 86h10M108 102h10" stroke="#94a3b8" strokeWidth="5" strokeLinecap="round" />
      <path d="M220 56c-30 0-40 30-62 30" stroke="#475569" strokeWidth="7" fill="none" strokeLinecap="round" />
      <rect x="134" y="72" width="26" height="28" rx="6" fill="#10b981" />
      <rect x="128" y="78" width="6" height="16" rx="2" fill="#059669" />
      <path d="M120 52l-6 14h10l-6 14" stroke="#fbbf24" strokeWidth="4" fill="none" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M100 128l-6 8M120 132v10M140 128l6 8" stroke="#fb7185" strokeWidth="4" strokeLinecap="round" />
      <Sparkle x={196} y={128} size={6} />
    </IllustrationSvg>
  )
}

/** An open, empty box — nothing here yet (the default empty state). */
export function EmptyBoxIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={120} cy={92} r={70} tone="sky" />
      <GroundShadow cx={120} cy={160} rx={62} />
      <path d="M66 80l54-18 54 18-54 18Z" fill="#cbd5e1" />
      <path d="M66 80v56l54 18V98Z" fill="#e2e8f0" />
      <path d="M174 80v56l-54 18V98Z" fill="#cbd5e1" />
      <path d="M66 80l-18-18 54-18 18 18ZM174 80l18-18-54-18-18 18Z" fill="#f1f5f9" stroke="#cbd5e1" strokeWidth="2" strokeLinejoin="round" />
      <path d="M92 52c4-10 14-16 24-14" stroke="#34d399" strokeWidth="3" strokeDasharray="4 6" fill="none" strokeLinecap="round" />
      <Sparkle x={150} y={30} size={8} />
      <Sparkle x={46} y={110} size={5} fill="#34d399" />
    </IllustrationSvg>
  )
}
