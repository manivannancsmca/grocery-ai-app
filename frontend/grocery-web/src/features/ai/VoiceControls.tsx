import { Button, Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import type { SpeechOutput } from './useSpeechOutput'
import type { VoiceInput } from './useVoiceInput'
import { formatSeconds } from './voice'

/**
 * The microphone next to "Send": press to speak, press again to stop (the words then go into the input). While it
 * listens it turns red; while the server turns the recording into text it waits.
 */
export function MicButton({ voice, disabled }: { voice: VoiceInput; disabled?: boolean }) {
  if (!voice.supported) return null
  const listening = voice.state === 'recording'
  const busy = voice.state === 'starting' || voice.state === 'transcribing'
  return (
    <Button
      variant={listening ? 'danger' : 'secondary'}
      aria-label={listening ? 'Stop recording' : 'Speak your question'}
      aria-pressed={listening}
      title={listening ? 'Stop recording' : 'Speak your question'}
      disabled={(disabled && !listening) || busy}
      onClick={listening ? voice.stop : voice.start}
      className="shrink-0 px-3"
    >
      {busy ? <Spinner label={voice.state === 'starting' ? 'Waiting for the microphone' : 'Turning your speech into text'} /> : <Icon name={listening ? 'stop' : 'mic'} className="size-5" />}
    </Button>
  )
}

/**
 * What the microphone is doing, under the input: a pulsing "Listening… 0:07 / 1:00" with Cancel, "Turning your
 * speech into text…", or what went wrong (microphone blocked, nothing heard, service down…). Announced politely.
 */
export function VoiceStatusLine({ voice, speech }: { voice: VoiceInput; speech: SpeechOutput }) {
  const error = voice.error ?? speech.error
  return (
    <div role="status" aria-live="polite" className="min-h-0 text-xs">
      {voice.state === 'recording' ? (
        <div className="flex flex-wrap items-center gap-2 rounded-lg bg-red-50 px-2.5 py-1.5 text-red-800">
          <span aria-hidden="true" className="size-2.5 animate-pulse rounded-full bg-red-600" />
          <span className="font-medium">Listening…</span>
          <span className="tabular-nums">
            {formatSeconds(voice.seconds)} / {formatSeconds(voice.maxSeconds)}
          </span>
          {voice.heard && <span className="min-w-0 flex-1 truncate italic">“{voice.heard}”</span>}
          <button type="button" onClick={voice.cancel} className="ml-auto rounded px-1.5 py-0.5 font-medium underline-offset-2 hover:underline">
            Cancel
          </button>
        </div>
      ) : voice.state === 'transcribing' ? (
        <p className="text-slate-600">Turning your speech into text…</p>
      ) : voice.state === 'starting' ? (
        <p className="text-slate-600">Allow the microphone in your browser to speak your question.</p>
      ) : error ? (
        <p className="flex items-start gap-2 text-amber-800">
          <span className="min-w-0 flex-1">{error}</span>
          <button
            type="button"
            onClick={() => {
              voice.clearError()
              speech.clearError()
            }}
            aria-label="Dismiss the voice message"
            className="shrink-0 rounded px-1 text-amber-900 hover:bg-amber-100"
          >
            <Icon name="close" className="size-3.5" />
          </button>
        </p>
      ) : null}
    </div>
  )
}

/** "Listen" under a finished answer; "Stop" while it is read aloud. */
export function ListenButton({ messageKey, text, speech }: { messageKey: string; text: string; speech: SpeechOutput }) {
  if (!speech.supported || !text.trim()) return null
  const speaking = speech.speakingKey === messageKey
  const loading = speech.loadingKey === messageKey
  return (
    <button
      type="button"
      onClick={() => (speaking || loading ? speech.stop() : speech.speak(messageKey, text))}
      aria-label={speaking || loading ? 'Stop reading this answer' : 'Read this answer aloud'}
      aria-pressed={speaking}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
        speaking ? 'bg-emerald-100 text-emerald-800' : 'text-slate-500 hover:bg-slate-100 hover:text-slate-800',
      )}
    >
      {loading ? <Spinner label="Preparing the audio" /> : <Icon name={speaking ? 'stop' : 'speaker'} className="size-3.5" />}
      {speaking ? 'Stop' : loading ? 'Preparing…' : 'Listen'}
    </button>
  )
}
