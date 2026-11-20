import { vi } from 'vitest'

/**
 * Stand-ins for the browser's voice features, which jsdom does not have: the microphone (`getUserMedia`) with
 * `MediaRecorder`, audio playback (`Audio` and object URLs), and the Web Speech API (`SpeechRecognition`,
 * `speechSynthesis`). Each installer returns handles the test drives; `uninstallFakeVoice()` removes them all.
 */

const restorers: (() => void)[] = []

function define(target: object, name: string, value: unknown) {
  const previous = Object.getOwnPropertyDescriptor(target, name)
  Object.defineProperty(target, name, { value, configurable: true, writable: true })
  restorers.push(() => {
    if (previous) Object.defineProperty(target, name, previous)
    else delete (target as Record<string, unknown>)[name]
  })
}

export function uninstallFakeVoice() {
  while (restorers.length > 0) restorers.pop()?.()
  vi.unstubAllGlobals()
}

// ---- microphone + MediaRecorder (SERVER mode) ----------------------------------------------------------------------

export interface FakeMicrophone {
  /** The microphone's track: `stop` is called when the microphone is released. */
  track: { stop: ReturnType<typeof vi.fn> }
  getUserMedia: ReturnType<typeof vi.fn>
  recorders: FakeMediaRecorder[]
}

export class FakeMediaRecorder {
  static instances: FakeMediaRecorder[] = []
  static isTypeSupported(type: string) {
    return type === 'audio/webm;codecs=opus'
  }

  state: 'inactive' | 'recording' = 'inactive'
  mimeType: string
  ondataavailable: ((event: { data: Blob }) => void) | null = null
  onstop: (() => void) | null = null
  onerror: (() => void) | null = null

  constructor(_stream: unknown, options?: { mimeType?: string }) {
    this.mimeType = options?.mimeType ?? 'audio/webm'
    FakeMediaRecorder.instances.push(this)
  }

  start() {
    this.state = 'recording'
  }

  stop() {
    if (this.state === 'inactive') return
    this.state = 'inactive'
    this.ondataavailable?.({ data: new Blob(['x'.repeat(4096)], { type: this.mimeType }) })
    this.onstop?.()
  }
}

/** A working microphone; `deny` makes the permission prompt answer "blocked". */
export function installFakeMicrophone(options: { deny?: boolean } = {}): FakeMicrophone {
  const track = { stop: vi.fn() }
  const getUserMedia = options.deny
    ? vi.fn(() => Promise.reject(new DOMException('Permission denied', 'NotAllowedError')))
    : vi.fn(() => Promise.resolve({ getTracks: () => [track] }))
  define(navigator, 'mediaDevices', { getUserMedia })
  FakeMediaRecorder.instances = []
  vi.stubGlobal('MediaRecorder', FakeMediaRecorder)
  return { track, getUserMedia, recorders: FakeMediaRecorder.instances }
}

// ---- audio playback (SERVER mode answers) ----------------------------------------------------------------------------

export class FakeAudioElement {
  static instances: FakeAudioElement[] = []
  src: string
  paused = true
  onended: (() => void) | null = null
  onerror: (() => void) | null = null
  play = vi.fn(() => {
    this.paused = false
    return Promise.resolve()
  })
  pause = vi.fn(() => {
    this.paused = true
  })

  constructor(src: string) {
    this.src = src
    FakeAudioElement.instances.push(this)
  }

  /** The answer has been played to the end. */
  finish() {
    this.paused = true
    this.onended?.()
  }
}

export function installFakeAudioPlayback() {
  FakeAudioElement.instances = []
  vi.stubGlobal('Audio', FakeAudioElement)
  const revoked: string[] = []
  define(URL, 'createObjectURL', vi.fn(() => `blob:answer-${FakeAudioElement.instances.length + 1}`))
  define(URL, 'revokeObjectURL', vi.fn((url: string) => revoked.push(url)))
  return { players: FakeAudioElement.instances, revoked }
}

// ---- Web Speech API (BROWSER mode) ---------------------------------------------------------------------------------

export class FakeRecognition {
  static instances: FakeRecognition[] = []
  lang = ''
  interimResults = false
  continuous = false
  maxAlternatives = 1
  onresult: ((event: unknown) => void) | null = null
  onerror: ((event: { error: string }) => void) | null = null
  onend: (() => void) | null = null
  start = vi.fn()
  stop = vi.fn(() => this.onend?.())
  abort = vi.fn(() => {
    this.onerror?.({ error: 'aborted' })
    this.onend?.()
  })

  constructor() {
    FakeRecognition.instances.push(this)
  }

  /** The recognizer heard `words` (a final result), then stopped listening. */
  hear(words: string) {
    const result = Object.assign([{ transcript: words }], { isFinal: true })
    this.onresult?.({ resultIndex: 0, results: [result] })
    this.onend?.()
  }

  fail(error: string) {
    this.onerror?.({ error })
    this.onend?.()
  }
}

export function installFakeSpeechApi() {
  FakeRecognition.instances = []
  vi.stubGlobal('SpeechRecognition', FakeRecognition)
  const spoken: string[] = []
  const utterances: { text: string; onend: (() => void) | null }[] = []
  class FakeUtterance {
    text: string
    lang = ''
    onend: (() => void) | null = null
    onerror: ((event: { error: string }) => void) | null = null
    constructor(text: string) {
      this.text = text
    }
  }
  const synthesis = {
    speak: vi.fn((utterance: FakeUtterance) => {
      spoken.push(utterance.text)
      utterances.push(utterance)
    }),
    cancel: vi.fn(),
  }
  vi.stubGlobal('SpeechSynthesisUtterance', FakeUtterance)
  define(window, 'speechSynthesis', synthesis)
  return { recognitions: FakeRecognition.instances, synthesis, spoken, utterances }
}
