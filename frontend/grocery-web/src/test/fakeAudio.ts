import { vi } from 'vitest'

/**
 * A silent stand-in for the Web Audio API (jsdom has none): records how many contexts were created and how
 * many tones were started, so tests can tell whether the app tried to beep. Nothing is ever played.
 * Undo it with `vi.unstubAllGlobals()`.
 */
export function installFakeAudio() {
  const record = { contexts: 0, tonesStarted: 0 }

  class FakeAudioContext {
    // Like a browser before any user gesture: suspended until resumed.
    state: AudioContextState = 'suspended'
    currentTime = 0
    destination = {}

    constructor() {
      record.contexts++
    }

    resume() {
      this.state = 'running'
      return Promise.resolve()
    }

    close() {
      this.state = 'closed'
      return Promise.resolve()
    }

    createOscillator() {
      return {
        type: 'sine',
        frequency: { value: 0, setValueAtTime: () => undefined },
        connect: () => undefined,
        start: () => {
          record.tonesStarted++
        },
        stop: () => undefined,
      }
    }

    createGain() {
      return {
        gain: { value: 1, setValueAtTime: () => undefined, exponentialRampToValueAtTime: () => undefined },
        connect: () => undefined,
      }
    }
  }

  vi.stubGlobal('AudioContext', FakeAudioContext)
  return record
}
