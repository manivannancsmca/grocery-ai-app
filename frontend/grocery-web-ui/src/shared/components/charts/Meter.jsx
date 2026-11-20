import { cn } from '../../utils/cn'

/** @typedef {'good' | 'warning' | 'critical'} MeterTone */

/**
 * Fill and a lighter track of the same hue, so the state reads across the whole bar.
 * @type {Record<MeterTone, { fill: string, track: string }>}
 */
const TONES = {
  good: { fill: 'bg-gradient-to-r from-emerald-500 to-teal-500', track: 'bg-emerald-100' },
  warning: { fill: 'bg-gradient-to-r from-amber-400 to-orange-500', track: 'bg-amber-100' },
  critical: { fill: 'bg-gradient-to-r from-red-500 to-rose-500', track: 'bg-red-100' },
}

/**
 * @typedef {object} MeterProps
 * @property {string} label
 * @property {number} ratio 0–1
 * @property {string} valueText The figure shown beside the label (e.g. "92%").
 * @property {MeterTone} [tone]
 * @property {string} [className]
 */

/**
 * One ratio against its whole (a success rate, a share used), as an accessible `meter`.
 * @param {MeterProps} props
 */
export function Meter({ label, ratio, valueText, tone = 'good', className }) {
  const clamped = Math.max(0, Math.min(1, Number.isFinite(ratio) ? ratio : 0))
  const colours = TONES[tone]
  return (
    <div className={cn('min-w-0', className)}>
      <div className="mb-1.5 flex items-baseline justify-between gap-3 text-sm">
        <span className="font-medium text-slate-700">{label}</span>
        <span className="font-semibold text-slate-900">{valueText}</span>
      </div>
      <div
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(clamped * 100)}
        aria-valuetext={valueText}
        className={cn('h-2.5 overflow-hidden rounded-full', colours.track)}
      >
        <div className={cn('chart-grow-x h-full rounded-full', colours.fill)} style={{ width: `${clamped * 100}%` }} />
      </div>
    </div>
  )
}
