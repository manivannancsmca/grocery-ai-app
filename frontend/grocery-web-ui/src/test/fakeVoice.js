import { vi } from 'vitest'

/**
 * Stand-ins for the browser's voice features, which jsdom does not have: the microphone (`getUserMedia`) with
 * `MediaRecorder`, audio playback (`Audio` and object URLs), and the Web Speech API (`SpeechRecognition`,
 * `speechSynthesis`). Each installer returns handles the test drives; `uninstallFakeVoice()` removes them all.
 */

const restorers = []

function define(target, name, value) {
  const previous = Object.getOwnPropertyDescriptor(target, name)
  Object.defineProperty(target, name, { value, configurable: true, writable: true })
  restorers.push(() => {
    if (previous) Object.defineProperty(target, name, previous)
    else delete target[name]
  })
}

export function uninstallFakeVoice() {
  while (restorers.length > 0) restorers.pop()?.()
  vi.unstubAllGlobals()
}

// ---- microphone + MediaRecorder (SERVER mode) ----------------------------------------------------------------------

export class FakeMediaRecorder {
  static instances = []
  static isTypeSupported(type) {
    return type === 'audio/webm;codecs=opus'
  }

  state = 'inactive'
  mimeType
  ondataavailable = null
  onstop = null
  onerror = null

  constructor(_stream, options) {
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
export function installFakeMicrophone(options = {}) {
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
  static instances = []
  src
  paused = true
  onended = null
  onerror = null
  play = vi.fn(() => {
    this.paused = false
    return Promise.resolve()
  })
  pause = vi.fn(() => {
    this.paused = true
  })

  constructor(src) {
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
  const revoked = []
  define(
    URL,
    'createObjectURL',
    vi.fn(() => `blob:answer-${FakeAudioElement.instances.length + 1}`),
  )
  define(
    URL,
    'revokeObjectURL',
    vi.fn((url) => revoked.push(url)),
  )
  return { players: FakeAudioElement.instances, revoked }
}

// ---- Web Speech API (BROWSER mode) ---------------------------------------------------------------------------------

export class FakeRecognition {
  static instances = []
  lang = ''
  interimResults = false
  continuous = false
  maxAlternatives = 1
  onresult = null
  onerror = null
  onend = null
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
  hear(words) {
    const result = Object.assign([{ transcript: words }], { isFinal: true })
    this.onresult?.({ resultIndex: 0, results: [result] })
    this.onend?.()
  }

  fail(error) {
    this.onerror?.({ error })
    this.onend?.()
  }
}

export function installFakeSpeechApi() {
  FakeRecognition.instances = []
  vi.stubGlobal('SpeechRecognition', FakeRecognition)
  const spoken = []
  const utterances = []
  class FakeUtterance {
    text
    lang = ''
    onend = null
    onerror = null
    constructor(text) {
      this.text = text
    }
  }
  const synthesis = {
    speak: vi.fn((utterance) => {
      spoken.push(utterance.text)
      utterances.push(utterance)
    }),
    cancel: vi.fn(),
  }
  vi.stubGlobal('SpeechSynthesisUtterance', FakeUtterance)
  define(window, 'speechSynthesis', synthesis)
  return { recognitions: FakeRecognition.instances, synthesis, spoken, utterances }
}
