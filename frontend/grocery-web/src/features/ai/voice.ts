import { ApiError, hasErrorCode, problemNumber } from '../../shared/api/problemDetail'
import { VOICE_ERRORS } from './api'

/**
 * Voice helpers shared by the microphone (speech to text) and the "Listen" button (text to speech): the browser
 * features each voice mode needs, the text an answer is read as, and every failure in plain words.
 */

// ---- browser features --------------------------------------------------------------------------------------------

/** The Web Speech API's recognition object: not in TypeScript's DOM types, and prefixed in Chrome and Safari. */
interface RecognitionAlternative {
  transcript: string
}
interface RecognitionResult {
  readonly isFinal: boolean
  readonly length: number
  readonly [index: number]: RecognitionAlternative
}
export interface RecognitionEvent {
  readonly resultIndex: number
  readonly results: ArrayLike<RecognitionResult>
}
export interface Recognition {
  lang: string
  interimResults: boolean
  continuous: boolean
  maxAlternatives: number
  onresult: ((event: RecognitionEvent) => void) | null
  onerror: ((event: { error: string }) => void) | null
  onend: (() => void) | null
  start(): void
  stop(): void
  abort(): void
}
export type RecognitionConstructor = new () => Recognition

/** The browser's speech recognition (`BROWSER` mode), or null where there is none (Firefox). */
export function speechRecognition(): RecognitionConstructor | null {
  if (typeof window === 'undefined') return null
  const scope = window as unknown as { SpeechRecognition?: RecognitionConstructor; webkitSpeechRecognition?: RecognitionConstructor }
  return scope.SpeechRecognition ?? scope.webkitSpeechRecognition ?? null
}

/** The microphone can be recorded here (`SERVER` mode): needs a secure page (https or localhost) and MediaRecorder. */
export function canRecordAudio(): boolean {
  return typeof window !== 'undefined' && typeof navigator !== 'undefined' && typeof navigator.mediaDevices?.getUserMedia === 'function' && typeof MediaRecorder !== 'undefined'
}

/** The browser can read text aloud by itself (`BROWSER` mode). */
export function canSynthesizeSpeech(): boolean {
  return typeof window !== 'undefined' && 'speechSynthesis' in window && typeof SpeechSynthesisUtterance !== 'undefined'
}

/** Compressed formats first (small uploads); Safari records MP4/AAC. Empty: let the browser choose. */
const RECORDING_TYPES = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4']

export function pickRecordingType(): string {
  if (typeof MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') return ''
  return RECORDING_TYPES.find((type) => MediaRecorder.isTypeSupported(type)) ?? ''
}

/** The language spoken to the browser's recognizer and synthesizer: the browser's own, else Indian English. */
export function speechLanguage(): string {
  return (typeof navigator !== 'undefined' && navigator.language) || 'en-IN'
}

// ---- what is read aloud -------------------------------------------------------------------------------------------

/** An answer as plain words (the server does the same in `SERVER` mode): links keep their label, marks go, lines become sentences. */
export function toSpeechText(markdown: string): string {
  const text = markdown
    .replace(/\[([^\]\n]+)\]\([^)\s]*\)/g, '$1')
    .replace(/`{1,3}/g, '')
    .replace(/(\*\*|\*)(\S[^*\n]*?)\1/g, '$2')
  const lines = text
    .split('\n')
    .map((line) => line.replace(/^\s{0,3}#{1,6}\s+/, '').replace(/^\s*[-*+]\s+/, '').replace(/ {2,}/g, ' ').trim())
    .filter((line) => line.length > 0)
  return lines.reduce((spoken, line) => (spoken ? `${spoken}${/[.!?:;,]$/.test(spoken) ? ' ' : '. '}${line}` : line), '')
}

/** At most `max` characters, cut after the last full sentence that fits. */
export function clipForSpeech(text: string, max: number): string {
  if (text.length <= max) return text
  const cut = text.slice(0, max)
  const end = Math.max(cut.lastIndexOf('. '), cut.lastIndexOf('! '), cut.lastIndexOf('? '))
  return end > max / 2 ? cut.slice(0, end + 1) : cut
}

/** Sentences grouped into pieces of at most `max` characters: long single utterances stop early in some browsers. */
export function speechChunks(text: string, max = 200): string[] {
  const sentences = text.match(/[^.!?]+[.!?]*\s*/g) ?? [text]
  const chunks: string[] = []
  let current = ''
  for (const sentence of sentences) {
    if (current && (current + sentence).length > max) {
      chunks.push(current.trim())
      current = ''
    }
    current += sentence
  }
  if (current.trim()) chunks.push(current.trim())
  return chunks
}

/** 7 → "0:07", 65 → "1:05". */
export function formatSeconds(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds))
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`
}

// ---- failures in plain words --------------------------------------------------------------------------------------

export const NO_SPEECH_MESSAGE = 'I did not catch any words. Please try again, a little closer to the microphone.'

/** getUserMedia failed. */
export function microphoneErrorMessage(error: unknown): string {
  const name = error instanceof DOMException || error instanceof Error ? error.name : ''
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return 'Microphone access is blocked. Allow the microphone for this site in your browser settings, then try again.'
  }
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return 'No microphone was found. Connect one and try again.'
  if (name === 'NotReadableError' || name === 'AbortError') return 'The microphone is busy in another app. Close it there and try again.'
  return 'The microphone could not be started. You can type your question instead.'
}

/** The browser's own recognizer failed (`BROWSER` mode). */
export function recognitionErrorMessage(code: string): string {
  switch (code) {
    case 'not-allowed':
    case 'service-not-allowed':
      return 'Microphone access is blocked. Allow the microphone for this site in your browser settings, then try again.'
    case 'audio-capture':
      return 'No microphone was found. Connect one and try again.'
    case 'no-speech':
      return NO_SPEECH_MESSAGE
    case 'network':
      return 'Speech recognition needs an internet connection right now. You can type your question instead.'
    case 'language-not-supported':
      return 'Speech recognition does not support your language here. You can type your question instead.'
    default:
      return 'Speech recognition stopped. Please try again or type your question.'
  }
}

/** A refused voice request (`SERVER` mode). */
export function voiceErrorMessage(error: unknown): string {
  if (hasErrorCode(error, VOICE_ERRORS.noSpeech)) return NO_SPEECH_MESSAGE
  if (hasErrorCode(error, VOICE_ERRORS.unsupported, VOICE_ERRORS.unreadable, VOICE_ERRORS.empty)) {
    return 'The recording could not be understood. Please try again.'
  }
  if (hasErrorCode(error, VOICE_ERRORS.tooLarge)) return 'The recording is too long. Please ask a shorter question.'
  if (hasErrorCode(error, VOICE_ERRORS.rateLimited)) {
    const seconds = problemNumber(error, 'retryAfterSeconds')
    return `You have used voice a lot in the last hour.${seconds ? ` Try again in about ${Math.max(1, Math.ceil(seconds / 60))} min.` : ''} You can still type.`
  }
  if (hasErrorCode(error, VOICE_ERRORS.timeout) || (error instanceof ApiError && error.kind === 'timeout')) {
    return 'The voice service took too long. Please try again or type your question.'
  }
  return 'Voice is not available right now. You can type your question instead.'
}
