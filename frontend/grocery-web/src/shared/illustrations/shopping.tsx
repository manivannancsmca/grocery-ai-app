import { Backdrop, GroundShadow, IllustrationSvg, type IllustrationProps, LeafMark, Sparkle } from './Illustration'

/** A kraft paper bag full of fresh groceries — the storefront hero. */
export function GroceryBagIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 320 260" {...props}>
      <Backdrop cx={160} cy={138} r={112} />
      <GroundShadow cx={160} cy={244} rx={92} ry={8} />
      {/* Baguette */}
      <g transform="rotate(14 172 95)">
        <rect x="158" y="34" width="28" height="118" rx="14" fill="#e8a23a" />
        <path d="M164 56l16-6M164 76l16-6M164 96l16-6M164 116l16-6" stroke="#c97f1d" strokeWidth="3" strokeLinecap="round" />
      </g>
      {/* Leafy greens */}
      <path d="M120 120c-18-22-20-50-4-70 10 20 14 44 4 70Z" fill="#16a34a" />
      <path d="M128 122c-4-30 8-56 30-66 2 24-8 48-30 66Z" fill="#22c55e" />
      <path d="M112 124c-22-10-34-30-32-52 20 8 32 28 32 52Z" fill="#4ade80" />
      <path d="M120 120c-1-20 2-40 0-62M128 122c4-20 12-40 26-60M112 124c-8-14-16-28-28-46" stroke="#14532d" strokeOpacity="0.35" strokeWidth="2" fill="none" strokeLinecap="round" />
      {/* Carrots */}
      <g transform="rotate(-18 214 96)">
        <path d="M206 74l16 0-8 66Z" fill="#fb923c" />
        <path d="M209 90h9M210 104h7" stroke="#ea580c" strokeWidth="2.5" strokeLinecap="round" />
        <path d="M214 74c-6-10-6-20 0-26 4 8 4 18 0 26ZM214 74c4-10 12-16 20-16-4 8-12 14-20 16ZM214 74c-6-8-14-12-22-10 4 6 12 10 22 10Z" fill="#22c55e" />
      </g>
      {/* Tomato */}
      <circle cx="196" cy="118" r="17" fill="#ef4444" />
      <circle cx="190" cy="112" r="4" fill="#fff" opacity="0.5" />
      <path d="M196 101l3 5 6-2-4 5 5 3-6 1-1 5-3-4-4 3 1-5-6-2 6-2Z" fill="#16a34a" />
      {/* Bag */}
      <path d="M94 128h132l-11 104a8 8 0 0 1-8 7H113a8 8 0 0 1-8-7Z" fill="#f2c897" />
      <path d="M94 128h132l-2 18H96Z" fill="#e3ab6b" />
      <path d="M226 128l-11 104a8 8 0 0 1-8 7h-20l14-111Z" fill="#000" opacity="0.06" />
      <circle cx="160" cy="186" r="24" fill="#fff" opacity="0.92" />
      <LeafMark x={160} y={186} size={30} />
      <Sparkle x={64} y={70} size={9} />
      <Sparkle x={262} y={54} size={7} fill="#34d399" />
      <Sparkle x={270} y={168} size={6} />
      <circle cx="58" cy="168" r="5" fill="#34d399" opacity="0.7" />
    </IllustrationSvg>
  )
}

/** A rider on an emerald scooter with a delivery box — fast delivery. */
export function DeliveryScooterIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 320 220" {...props}>
      <Backdrop cx={170} cy={112} r={98} tone="amber" />
      <path d="M18 128h40M8 146h50M26 164h30" stroke="#cbd5e1" strokeWidth="5" strokeLinecap="round" />
      <GroundShadow cx={165} cy={200} rx={110} ry={7} />
      {/* Delivery box */}
      <rect x="66" y="80" width="64" height="52" rx="7" fill="#fbbf24" />
      <rect x="66" y="80" width="64" height="14" rx="7" fill="#f59e0b" />
      <circle cx="98" cy="114" r="12" fill="#fff" opacity="0.9" />
      <LeafMark x={98} y={114} size={16} />
      {/* Rider */}
      <path d="M142 130l26 22" stroke="#334155" strokeWidth="13" strokeLinecap="round" />
      <path d="M128 84c12-12 32-10 40 6l-4 40h-34Z" fill="#38bdf8" />
      <path d="M160 92l42 4" stroke="#38bdf8" strokeWidth="11" strokeLinecap="round" />
      <circle cx="207" cy="96" r="6" fill="#f1c27d" />
      <circle cx="150" cy="62" r="19" fill="#059669" />
      <path d="M152 53h14a5 5 0 0 1 5 5v8a5 5 0 0 1-5 5h-14Z" fill="#1e293b" opacity="0.85" />
      <path d="M135 56a16 16 0 0 1 10-12" stroke="#fff" strokeOpacity="0.45" strokeWidth="3" fill="none" strokeLinecap="round" />
      {/* Scooter */}
      <path d="M70 150c0-12 10-20 22-20h56l14 30H80c-6 0-10-4-10-10Z" fill="#10b981" />
      <rect x="120" y="150" width="78" height="12" rx="6" fill="#059669" />
      <path d="M192 160l14-66" stroke="#059669" strokeWidth="10" strokeLinecap="round" />
      <path d="M198 90l26-5" stroke="#334155" strokeWidth="6" strokeLinecap="round" />
      <path d="M200 170c10-26 40-26 50 0" stroke="#10b981" strokeWidth="10" fill="none" strokeLinecap="round" />
      <circle cx="224" cy="96" r="5" fill="#fde68a" />
      <circle cx="98" cy="176" r="22" fill="#334155" />
      <circle cx="98" cy="176" r="9" fill="#cbd5e1" />
      <circle cx="225" cy="176" r="22" fill="#334155" />
      <circle cx="225" cy="176" r="9" fill="#cbd5e1" />
      <Sparkle x={262} y={46} size={8} fill="#34d399" />
      <Sparkle x={52} y={52} size={6} />
    </IllustrationSvg>
  )
}

/** An empty shopping cart with a tomato hopping in. */
export function EmptyCartIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={124} cy={92} r={76} />
      <GroundShadow cx={124} cy={162} rx={70} />
      <path d="M150 58c14-16 32-22 46-18" stroke="#34d399" strokeWidth="3" strokeDasharray="5 7" fill="none" strokeLinecap="round" />
      <path d="M36 46h22l16 72h96" stroke="#475569" strokeWidth="7" fill="none" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M62 62h126l-14 46H76Z" fill="#fff" stroke="#475569" strokeWidth="6" strokeLinejoin="round" />
      <path d="M98 64l2 42M124 64v42M150 64l-2 42M70 85h112" stroke="#cbd5e1" strokeWidth="3" strokeLinecap="round" />
      <circle cx="88" cy="140" r="11" fill="#334155" />
      <circle cx="88" cy="140" r="4" fill="#fff" />
      <circle cx="160" cy="140" r="11" fill="#334155" />
      <circle cx="160" cy="140" r="4" fill="#fff" />
      <circle cx="204" cy="38" r="13" fill="#ef4444" />
      <circle cx="199" cy="33" r="3" fill="#fff" opacity="0.55" />
      <path d="M204 24l2 4 5-1-3 4 4 3-5 0-1 4-2-3-3 2 1-4-5-2 5-1Z" fill="#16a34a" />
      <Sparkle x={30} y={22} size={7} />
      <Sparkle x={214} y={104} size={6} fill="#34d399" />
    </IllustrationSvg>
  )
}

/** A magnifying glass over produce with a question mark — nothing found. */
export function SearchIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={120} cy={90} r={76} tone="sky" />
      <GroundShadow cx={120} cy={164} rx={66} />
      {/* Produce behind the lens */}
      <circle cx="78" cy="128" r="18" fill="#ef4444" />
      <path d="M78 110l2 4 5-1-3 4 4 3-5 0-1 4-2-3-3 2 1-4-5-2 5-1Z" fill="#16a34a" />
      <g transform="rotate(30 170 120)">
        <path d="M162 92h16l-8 60Z" fill="#fb923c" />
        <path d="M170 92c-5-8-5-16 0-20 4 6 4 14 0 20ZM170 92c4-8 10-12 16-12-3 6-9 10-16 12Z" fill="#22c55e" />
      </g>
      {/* Magnifier */}
      <path d="M144 112l34 34" stroke="#475569" strokeWidth="14" strokeLinecap="round" />
      <circle cx="112" cy="80" r="44" fill="#f0f9ff" fillOpacity="0.85" stroke="#475569" strokeWidth="9" />
      <path d="M86 62a30 30 0 0 1 22-14" stroke="#fff" strokeWidth="5" fill="none" strokeLinecap="round" />
      <path d="M102 70a10 10 0 1 1 14 9c-3 1-4 4-4 7v3" stroke="#10b981" strokeWidth="7" fill="none" strokeLinecap="round" />
      <circle cx="112" cy="101" r="4.5" fill="#10b981" />
      <Sparkle x={196} y={36} size={7} />
      <Sparkle x={40} y={60} size={5} fill="#34d399" />
    </IllustrationSvg>
  )
}

/** A serving cloche with steam, fork and knife — restaurants. */
export function RestaurantIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={120} cy={92} r={76} tone="amber" />
      <GroundShadow cx={120} cy={160} rx={74} />
      <path d="M100 40c-6-8 6-14 0-22M120 36c-6-8 6-14 0-22M140 40c-6-8 6-14 0-22" stroke="#94a3b8" strokeWidth="4" fill="none" strokeLinecap="round" />
      <ellipse cx="120" cy="138" rx="78" ry="12" fill="#e2e8f0" />
      <path d="M58 132a62 62 0 0 1 124 0Z" fill="#10b981" />
      <path d="M76 116a46 46 0 0 1 28-32" stroke="#fff" strokeOpacity="0.5" strokeWidth="6" fill="none" strokeLinecap="round" />
      <rect x="54" y="128" width="132" height="10" rx="5" fill="#059669" />
      <circle cx="120" cy="66" r="8" fill="#059669" />
      {/* Fork and knife */}
      <path d="M26 64v32M20 64v14a6 6 0 0 0 12 0V64M26 96v54" stroke="#64748b" strokeWidth="4" strokeLinecap="round" fill="none" />
      <path d="M214 150V64c-10 6-12 22-10 40h10" stroke="#64748b" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" fill="none" />
      <Sparkle x={196} y={36} size={7} />
      <Sparkle x={46} y={30} size={5} fill="#34d399" />
    </IllustrationSvg>
  )
}

/** A full bag with a big check mark and confetti — order placed or confirmed. */
export function OrderSuccessIllustration(props: IllustrationProps) {
  return (
    <IllustrationSvg viewBox="0 0 240 180" {...props}>
      <Backdrop cx={120} cy={92} r={76} />
      <GroundShadow cx={112} cy={164} rx={60} />
      <path d="M80 70a26 26 0 0 1 52 0" stroke="#c98a4b" strokeWidth="6" fill="none" strokeLinecap="round" />
      <path d="M64 68h96l-8 88a6 6 0 0 1-6 6H78a6 6 0 0 1-6-6Z" fill="#f2c897" />
      <path d="M64 68h96l-1 12H65Z" fill="#e3ab6b" />
      <circle cx="112" cy="116" r="15" fill="#fff" opacity="0.9" />
      <LeafMark x={112} y={116} size={20} />
      <circle cx="164" cy="64" r="26" fill="#10b981" />
      <path d="M152 64l8 8 16-16" stroke="#fff" strokeWidth="6" fill="none" strokeLinecap="round" strokeLinejoin="round" />
      <rect x="40" y="38" width="8" height="8" rx="2" fill="#fbbf24" transform="rotate(20 44 42)" />
      <rect x="196" y="112" width="8" height="8" rx="2" fill="#fb7185" transform="rotate(-15 200 116)" />
      <circle cx="206" cy="30" r="4" fill="#38bdf8" />
      <circle cx="34" cy="120" r="4" fill="#34d399" />
      <Sparkle x={200} y={146} size={6} />
      <Sparkle x={58} y={24} size={6} fill="#34d399" />
    </IllustrationSvg>
  )
}
