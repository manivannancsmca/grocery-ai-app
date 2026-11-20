/**
 * The new-order beep: two short tones made with the Web Audio API (no audio files, no dependencies).
 *
 * Browsers only let a page start audio after the user interacted with it, so the AudioContext is created (or
 * resumed) from a user gesture — `unlock()` — and `beep()` stays silent until that worked.
 */

/** @typedef {new () => AudioContext} AudioContextClass */

/** @returns {AudioContextClass | null} */
function audioContextClass() {
  const scope = globalThis
  return scope.AudioContext ?? scope.webkitAudioContext ?? null
}

/** Two rising tones, ~0.35 s in all. */
const TONES = [
  { frequency: 880, offset: 0 },
  { frequency: 1318.5, offset: 0.18 },
]
const TONE_SECONDS = 0.15
const VOLUME = 0.25

/**
 * @typedef {object} Beeper
 * @property {() => boolean} supported False where the browser has no Web Audio API.
 * @property {() => Promise<boolean>} unlock Creates or resumes the audio context; call it from a click or key press. Resolves to "sound can play".
 * @property {() => boolean} unlocked
 * @property {() => boolean} beep Plays the alert; false (and silent) while not unlocked.
 * @property {() => void} close
 */

/** @returns {Beeper} */
export function createBeeper() {
  let context = null

  const running = () => context !== null && context.state === 'running'

  return {
    supported: () => audioContextClass() !== null,
    unlocked: running,
    async unlock() {
      const AudioContextCtor = audioContextClass()
      if (!AudioContextCtor) return false
      try {
        if (context === null || context.state === 'closed') context = new AudioContextCtor()
        if (context.state === 'suspended') await context.resume()
        return running()
      } catch {
        return false
      }
    },
    beep() {
      if (!context || !running()) return false
      try {
        const start = context.currentTime
        for (const tone of TONES) {
          const oscillator = context.createOscillator()
          const gain = context.createGain()
          const at = start + tone.offset
          oscillator.type = 'sine'
          oscillator.frequency.setValueAtTime(tone.frequency, at)
          gain.gain.setValueAtTime(0.0001, at)
          gain.gain.exponentialRampToValueAtTime(VOLUME, at + 0.02)
          gain.gain.exponentialRampToValueAtTime(0.0001, at + TONE_SECONDS)
          oscillator.connect(gain)
          gain.connect(context.destination)
          oscillator.start(at)
          oscillator.stop(at + TONE_SECONDS + 0.01)
        }
        return true
      } catch {
        return false
      }
    },
    close() {
      const current = context
      context = null
      if (current && current.state !== 'closed') void current.close().catch(() => undefined)
    },
  }
}

/** The "Sound alerts" switch, remembered per browser. Default on. */
export const SOUND_PREFERENCE_KEY = 'grocery.storeOrders.sound'

/** @returns {boolean} */
export function readSoundPreference() {
  try {
    return localStorage.getItem(SOUND_PREFERENCE_KEY) !== 'off'
  } catch {
    return true
  }
}

/**
 * @param {boolean} on
 * @returns {void}
 */
export function writeSoundPreference(on) {
  try {
    localStorage.setItem(SOUND_PREFERENCE_KEY, on ? 'on' : 'off')
  } catch {
    // Private mode / storage disabled: the choice lasts for this page only.
  }
}
