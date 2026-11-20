import { Backdrop, GroundShadow, IllustrationSvg, type IllustrationProps, LeafMark, Sparkle } from './Illustration'

/** A parcel and a receipt — no orders yet / order history. */
export function NoOrdersIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={120} cy={90} r={76} />
      <GroundShadow cx={120} cy={162} rx={78} />
      {/* Parcel */}
      <path d="M52 92l50-18 50 18-50 18Z" fill="#fcd9a4" />
      <path d="M52 92v52l50 18v-52Z" fill="#f2c897" />
      <path d="M152 92v52l-50 18v-52Z" fill="#e3ab6b" />
      <path d="M77 83l50 18v14" stroke="#10b981" strokeWidth="9" fill="none" />
      {/* Receipt */}
      <path d="M140 30h62v104l-8-6-8 6-8-6-8 6-8-6-8 6-7-6-7 6Z" fill="#fff" stroke="#e2e8f0" strokeWidth="2" />
      <path d="M152 50h38M152 64h28M152 78h34M152 92h22" stroke="#cbd5e1" strokeWidth="5" strokeLinecap="round" />
      <circle cx="186" cy="110" r="11" fill="#10b981" />
      <path d="M181 110l4 4 7-7" stroke="#fff" strokeWidth="3" fill="none" strokeLinecap="round" strokeLinejoin="round" />
      <Sparkle x={36} y={50} size={7} />
      <Sparkle x={218} y={150} size={5} fill="#34d399" />
    </IllustrationSvg>
  )
}

/** A folded map with a location pin — delivery addresses. */
export function MapPinIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={120} cy={90} r={76} tone="sky" />
      <GroundShadow cx={120} cy={160} rx={80} />
      <path d="M40 66l52-16v96l-52 16Z" fill="#a7f3d0" />
      <path d="M92 50l56 16v96l-56-16Z" fill="#d1fae5" />
      <path d="M148 66l52-16v96l-52 16Z" fill="#a7f3d0" />
      <path d="M46 120c30-10 50 6 80-6s50-24 70-14" stroke="#fff" strokeWidth="6" fill="none" strokeLinecap="round" />
      <path d="M70 70c10 24 4 50 14 76" stroke="#fff" strokeWidth="4" fill="none" strokeLinecap="round" strokeDasharray="2 8" />
      <path d="M124 26a28 28 0 0 1 28 28c0 22-28 50-28 50s-28-28-28-50a28 28 0 0 1 28-28Z" fill="#f43f5e" />
      <circle cx="124" cy="54" r="11" fill="#fff" />
      <ellipse cx="124" cy="108" rx="12" ry="4" fill="#0f172a" opacity="0.15" />
      <Sparkle x={196} y={30} size={7} />
    </IllustrationSvg>
  )
}

/** A speech bubble with five stars — reviews and ratings. */
export function ReviewsIllustration(props: IllustrationProps) {
  const star = (cx: number, cy: number, r: number, fill: string) => {
    const points = Array.from({ length: 10 }, (_, i) => {
      const angle = (Math.PI / 5) * i - Math.PI / 2
      const radius = i % 2 === 0 ? r : r * 0.45
      return `${(cx + radius * Math.cos(angle)).toFixed(1)},${(cy + radius * Math.sin(angle)).toFixed(1)}`
    })
    return <polygon key={`${cx}-${cy}`} points={points.join(' ')} fill={fill} />
  }
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={120} cy={90} r={76} tone="amber" />
      <GroundShadow cx={120} cy={162} rx={62} />
      <path d="M44 40h140a14 14 0 0 1 14 14v52a14 14 0 0 1-14 14H96l-24 22v-22H44a14 14 0 0 1-14-14V54a14 14 0 0 1 14-14Z" fill="#fff" stroke="#e2e8f0" strokeWidth="2" />
      {[64, 89, 114, 139].map((x) => star(x, 78, 13, '#fbbf24'))}
      {star(164, 78, 13, '#fde68a')}
      <path d="M176 128a18 18 0 0 1 28 0 18 18 0 0 1 28 0c0 16-28 30-28 30s-28-14-28-30Z" fill="#fb7185" transform="scale(0.7) translate(64 52)" />
      <Sparkle x={210} y={34} size={7} fill="#34d399" />
      <Sparkle x={24} y={130} size={6} />
    </IllustrationSvg>
  )
}

/** A parcel with a return arrow — returns and refunds. */
export function ReturnsIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={120} cy={90} r={76} />
      <GroundShadow cx={112} cy={162} rx={62} />
      <path d="M60 88l52-18 52 18-52 18Z" fill="#fcd9a4" />
      <path d="M60 88v54l52 18v-54Z" fill="#f2c897" />
      <path d="M164 88v54l-52 18v-54Z" fill="#e3ab6b" />
      <path d="M86 79l52 18v14" stroke="#cbd5e1" strokeWidth="8" fill="none" />
      <circle cx="176" cy="54" r="30" fill="#10b981" />
      <path d="M190 60a15 15 0 1 1-4-17" stroke="#fff" strokeWidth="5.5" fill="none" strokeLinecap="round" />
      <path d="M189 34v10h-10" stroke="#fff" strokeWidth="5.5" fill="none" strokeLinecap="round" strokeLinejoin="round" />
      <Sparkle x={40} y={44} size={7} />
      <Sparkle x={210} y={132} size={5} fill="#34d399" />
    </IllustrationSvg>
  )
}

/** A ringing bell with a badge — notifications. */
export function NotificationsIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={120} cy={90} r={76} tone="amber" />
      <GroundShadow cx={120} cy={162} rx={52} />
      <path d="M70 52c-10 10-14 22-14 34M170 52c10 10 14 22 14 34M58 40c-14 14-20 30-20 46M182 40c14 14 20 30 20 46" stroke="#fcd34d" strokeWidth="5" fill="none" strokeLinecap="round" />
      <path d="M120 30a8 8 0 0 1 8 8v4c20 4 32 22 32 44v26l10 14H70l10-14V86c0-22 12-40 32-44v-4a8 8 0 0 1 8-8Z" fill="#fbbf24" />
      <path d="M94 70a28 28 0 0 1 16-20" stroke="#fff" strokeOpacity="0.55" strokeWidth="6" fill="none" strokeLinecap="round" />
      <path d="M106 130a14 14 0 0 0 28 0Z" fill="#f59e0b" />
      <circle cx="156" cy="50" r="16" fill="#f43f5e" />
      <text x="156" y="56" textAnchor="middle" fontSize="16" fontWeight="700" fill="#fff" fontFamily="system-ui, sans-serif">3</text>
    </IllustrationSvg>
  )
}

/** An ID card with an avatar — the customer's profile. */
export function ProfileIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={120} cy={90} r={76} />
      <GroundShadow cx={120} cy={160} rx={78} />
      <rect x="40" y="44" width="160" height="104" rx="14" fill="#fff" stroke="#e2e8f0" strokeWidth="2" />
      <rect x="40" y="44" width="160" height="26" rx="14" fill="#10b981" />
      <rect x="40" y="58" width="160" height="12" fill="#10b981" />
      <circle cx="84" cy="104" r="24" fill="#d1fae5" />
      <circle cx="84" cy="98" r="9" fill="#059669" />
      <path d="M68 120a16 16 0 0 1 32 0Z" fill="#059669" />
      <path d="M122 92h56M122 108h40M122 124h48" stroke="#cbd5e1" strokeWidth="6" strokeLinecap="round" />
      <LeafMark x={182} y={57} size={14} color="#fff" />
      <Sparkle x={212} y={36} size={7} />
    </IllustrationSvg>
  )
}

/** An envelope with a letter — email preferences. */
export function EmailIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={120} cy={90} r={76} tone="sky" />
      <GroundShadow cx={120} cy={162} rx={72} />
      <rect x="52" y="64" width="136" height="88" rx="10" fill="#10b981" />
      <rect x="66" y="34" width="108" height="86" rx="6" fill="#fff" stroke="#e2e8f0" strokeWidth="2" />
      <path d="M80 54h52M80 68h80M80 82h64" stroke="#cbd5e1" strokeWidth="5" strokeLinecap="round" />
      <path d="M52 74l68 44 68-44v68a10 10 0 0 1-10 10H62a10 10 0 0 1-10-10Z" fill="#34d399" />
      <path d="M52 152l52-38M188 152l-52-38" stroke="#059669" strokeWidth="3" />
      <circle cx="184" cy="56" r="16" fill="#fbbf24" />
      <path d="M177 56l5 5 9-9" stroke="#fff" strokeWidth="3.5" fill="none" strokeLinecap="round" strokeLinejoin="round" />
      <Sparkle x={36} y={44} size={6} />
    </IllustrationSvg>
  )
}

/** A friendly robot with sparkles — the AI shopping assistant. */
export function AssistantIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={120} cy={92} r={76} tone="sky" />
      <GroundShadow cx={120} cy={164} rx={52} />
      <path d="M120 40V24" stroke="#64748b" strokeWidth="4" strokeLinecap="round" />
      <circle cx="120" cy="20" r="7" fill="#fbbf24" />
      <rect x="72" y="40" width="96" height="74" rx="24" fill="#10b981" />
      <rect x="84" y="54" width="72" height="44" rx="16" fill="#ecfdf5" />
      <circle cx="104" cy="74" r="7" fill="#0f172a" />
      <circle cx="136" cy="74" r="7" fill="#0f172a" />
      <circle cx="106" cy="72" r="2" fill="#fff" />
      <circle cx="138" cy="72" r="2" fill="#fff" />
      <path d="M110 86a12 8 0 0 0 20 0" stroke="#0f172a" strokeWidth="3" fill="none" strokeLinecap="round" />
      <rect x="64" y="66" width="10" height="22" rx="5" fill="#059669" />
      <rect x="166" y="66" width="10" height="22" rx="5" fill="#059669" />
      <rect x="88" y="118" width="64" height="38" rx="14" fill="#34d399" />
      <circle cx="120" cy="137" r="8" fill="#fff" opacity="0.85" />
      <Sparkle x={190} y={42} size={10} />
      <Sparkle x={48} y={58} size={7} fill="#34d399" />
      <Sparkle x={196} y={120} size={6} fill="#38bdf8" />
    </IllustrationSvg>
  )
}
