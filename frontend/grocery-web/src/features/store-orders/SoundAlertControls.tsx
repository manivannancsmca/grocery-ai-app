import { Switch } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { useOrderAlerts } from './alertsContext'

const TOOL_CLASS = 'inline-flex h-10 items-center rounded-xl bg-white shadow-xs ring-1 ring-slate-200 ring-inset'

/**
 * The board toolbar's "Sound alerts" switch (remembered) or, while sound is on but the browser still blocks audio,
 * "Enable sound". On phones the switch shows a speaker instead of its words (its name stays "Sound alerts").
 */
export function SoundAlertControls() {
  const alerts = useOrderAlerts()
  if (!alerts) return null
  if (!alerts.soundSupported) {
    return (
      <span className={`${TOOL_CLASS} gap-1.5 px-2.5 text-xs text-slate-500`} title="Sound alerts are not available in this browser.">
        <Icon name="speaker" className="size-4 opacity-50 grayscale" />
        <span className="max-sm:sr-only">Sound alerts are not available in this browser.</span>
      </span>
    )
  }
  // Sound is on but the browser still blocks audio: one button that unlocks it (any click on the page does too).
  if (alerts.soundOn && !alerts.soundUnlocked) {
    return (
      <button
        type="button"
        onClick={alerts.enableSound}
        aria-label="Click to enable sound alerts"
        title="Browsers keep sound off until you click on the page"
        className="inline-flex h-10 items-center gap-1.5 rounded-xl bg-amber-50 px-3 text-sm font-semibold whitespace-nowrap text-amber-900 shadow-xs ring-1 ring-amber-300 ring-inset transition-colors hover:bg-amber-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-600"
      >
        <Icon name="speaker" className="size-4" />
        Enable sound
      </button>
    )
  }
  return (
    <div className={`${TOOL_CLASS} gap-2 px-3 max-sm:pr-0`} title={alerts.soundOn ? 'A beep for every new order' : 'New orders make no sound'}>
      <Icon name="speaker" className={alerts.soundOn ? 'size-4 sm:hidden' : 'size-4 opacity-50 grayscale sm:hidden'} />
      <Switch
        size="sm"
        label={<span className="whitespace-nowrap max-sm:sr-only">Sound alerts</span>}
        checked={alerts.soundOn}
        onChange={alerts.setSoundOn}
      />
    </div>
  )
}
