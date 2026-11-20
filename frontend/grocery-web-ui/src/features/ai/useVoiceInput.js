import { useCallback, useEffect, useRef, useState } from 'react'
import { transcribeAudio } from './api'
import {
  canRecordAudio,
  microphoneErrorMessage,
  NO_SPEECH_MESSAGE,
  pickRecordingType,
  recognitionErrorMessage,
  speechLanguage,
  speechRecognition,
  voiceErrorMessage,
} from './voice'

/** @typedef {import('./api').VoiceStatus} VoiceStatus */

/**
 * `starting`: waiting for the microphone permission; `transcribing`: the server turns the recording into text.
 * @typedef {'idle' | 'starting' | 'recording' | 'transcribing'} VoiceInputState
 */

/**
 * @typedef {object} VoiceInput
 * @property {boolean} supported A microphone button makes sense here (voice is on and this browser can do it).
 * @property {VoiceInputState} state
 * @property {number} seconds Seconds recorded so far.
 * @property {number} maxSeconds
 * @property {string} heard `BROWSER` mode: the words heard so far, while listening.
 * @property {string | null} error
 * @property {() => void} start
 * @property {() => void} stop Stops listening and turns what was said into text.
 * @property {() => void} cancel Stops listening and throws the recording away.
 * @property {() => void} clearError
 */

/**
 * @typedef {object} VoiceInputOptions
 * @property {(text: string) => void} onText The recognised words (never empty).
 * @property {() => void} [onStart] Called as recording starts (e.g. to stop an answer being read aloud, so the microphone does not hear it).
 */

/** Shorter recordings are almost always a mis-tap. */
const MIN_RECORDING_MS = 500

/**
 * The chat input's microphone. In `SERVER` mode it records with MediaRecorder and sends the recording to
 * `POST /api/ai/voice/transcriptions`; in `BROWSER` mode the browser's own speech recognition listens. Either way the
 * words go into the chat input (the user reviews and sends them), recording stops by itself after
 * `maxRecordingSeconds`, and the microphone is released when recording ends or the component unmounts.
 * @param {VoiceStatus} status
 * @param {VoiceInputOptions} arg1
 * @returns {VoiceInput}
 */
export function useVoiceInput(status, { onText, onStart }) {
  const mode = status.mode
  const supported =
    (mode === 'SERVER' && status.transcription && canRecordAudio()) ||
    (mode === 'BROWSER' && speechRecognition() !== null)
  const maxSeconds = Math.max(5, status.maxRecordingSeconds)

  const [state, setState] = useState('idle')
  const [seconds, setSeconds] = useState(0)
  const [heard, setHeard] = useState('')
  const [error, setError] = useState(null)

  const onTextRef = useRef(onText)
  const onStartRef = useRef(onStart)
  useEffect(() => {
    onTextRef.current = onText
    onStartRef.current = onStart
  })

  const stateRef = useRef('idle')
  const recorderRef = useRef(null)
  const streamRef = useRef(null)
  const recognitionRef = useRef(null)
  const timerRef = useRef(null)
  const transcriptionRef = useRef(null)
  const discardRef = useRef(false)
  const mountedRef = useRef(true)

  const update = useCallback((next) => {
    stateRef.current = next
    if (mountedRef.current) setState(next)
  }, [])

  /** Stops the timer and turns the microphone off. */
  const release = useCallback(() => {
    if (timerRef.current !== null) clearInterval(timerRef.current)
    timerRef.current = null
    streamRef.current?.getTracks().forEach((track) => track.stop())
    streamRef.current = null
    recorderRef.current = null
    recognitionRef.current = null
  }, [])

  const stop = useCallback(() => {
    if (stateRef.current !== 'recording') return
    if (recorderRef.current && recorderRef.current.state !== 'inactive') recorderRef.current.stop()
    recognitionRef.current?.stop()
  }, [])

  const cancel = useCallback(() => {
    discardRef.current = true
    transcriptionRef.current?.abort()
    transcriptionRef.current = null
    if (recorderRef.current && recorderRef.current.state !== 'inactive') recorderRef.current.stop()
    recognitionRef.current?.abort()
    release()
    if (mountedRef.current) setHeard('')
    update('idle')
  }, [release, update])

  /** Counts the seconds and stops at the limit. */
  const startTimer = useCallback(
    (startedAt) => {
      setSeconds(0)
      timerRef.current = setInterval(() => {
        const elapsed = (Date.now() - startedAt) / 1000
        if (mountedRef.current) setSeconds(Math.floor(elapsed))
        if (elapsed >= maxSeconds) stop()
      }, 250)
    },
    [maxSeconds, stop],
  )

  const transcribe = useCallback(
    (recording) => {
      const controller = new AbortController()
      transcriptionRef.current = controller
      update('transcribing')
      transcribeAudio(recording, controller.signal)
        .then((text) => {
          if (controller.signal.aborted || !mountedRef.current) return
          const words = text.trim()
          if (words) onTextRef.current(words)
          else setError(NO_SPEECH_MESSAGE)
        })
        .catch((failure) => {
          if (!controller.signal.aborted && mountedRef.current) setError(voiceErrorMessage(failure))
        })
        .finally(() => {
          if (transcriptionRef.current === controller) {
            transcriptionRef.current = null
            update('idle')
          }
        })
    },
    [update],
  )

  const startRecorder = useCallback(async () => {
    let stream
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } })
    } catch (failure) {
      update('idle')
      if (mountedRef.current) setError(microphoneErrorMessage(failure))
      return
    }
    if (!mountedRef.current || discardRef.current) {
      stream.getTracks().forEach((track) => track.stop())
      update('idle')
      return
    }
    const type = pickRecordingType()
    let recorder
    try {
      recorder = type ? new MediaRecorder(stream, { mimeType: type }) : new MediaRecorder(stream)
    } catch {
      stream.getTracks().forEach((track) => track.stop())
      update('idle')
      setError('This browser cannot record audio. You can type your question instead.')
      return
    }
    const chunks = []
    const startedAt = Date.now()
    streamRef.current = stream
    recorderRef.current = recorder
    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) chunks.push(event.data)
    }
    recorder.onstop = () => {
      const duration = Date.now() - startedAt
      const recording = new Blob(chunks, { type: recorder.mimeType || type || 'audio/webm' })
      release()
      if (discardRef.current || !mountedRef.current) {
        update('idle')
        return
      }
      if (duration < MIN_RECORDING_MS || recording.size === 0) {
        update('idle')
        setError('That was too short. Hold the microphone on while you ask your question.')
        return
      }
      if (recording.size > status.maxAudioBytes) {
        update('idle')
        setError('The recording is too long. Please ask a shorter question.')
        return
      }
      transcribe(recording)
    }
    recorder.onerror = () => {
      discardRef.current = true
      release()
      update('idle')
      if (mountedRef.current) setError('Recording stopped unexpectedly. Please try again.')
    }
    recorder.start(250)
    update('recording')
    startTimer(startedAt)
  }, [release, startTimer, status.maxAudioBytes, transcribe, update])

  const startRecognition = useCallback(() => {
    const Recognizer = speechRecognition()
    if (!Recognizer) return
    const recognition = new Recognizer()
    recognition.lang = speechLanguage()
    recognition.interimResults = true
    recognition.continuous = false
    recognition.maxAlternatives = 1
    let finalText = ''
    let failure = null
    recognition.onresult = (event) => {
      let interim = ''
      for (let index = event.resultIndex; index < event.results.length; index += 1) {
        const result = event.results[index]
        if (!result) continue
        if (result.isFinal) finalText += result[0]?.transcript ?? ''
        else interim += result[0]?.transcript ?? ''
      }
      if (mountedRef.current) setHeard(`${finalText} ${interim}`.trim())
    }
    recognition.onerror = (event) => {
      if (event.error !== 'aborted') failure = event.error
    }
    recognition.onend = () => {
      release()
      update('idle')
      if (!mountedRef.current) return
      setHeard('')
      if (discardRef.current) return
      const words = finalText.trim()
      if (words) onTextRef.current(words)
      else setError(failure ? recognitionErrorMessage(failure) : NO_SPEECH_MESSAGE)
    }
    recognitionRef.current = recognition
    try {
      recognition.start()
    } catch {
      release()
      update('idle')
      setError('Speech recognition could not start. Please try again.')
      return
    }
    update('recording')
    startTimer(Date.now())
  }, [release, startTimer, update])

  const start = useCallback(() => {
    if (!supported || stateRef.current !== 'idle') return
    setError(null)
    setHeard('')
    discardRef.current = false
    onStartRef.current?.()
    if (mode === 'SERVER') {
      update('starting')
      void startRecorder()
    } else {
      startRecognition()
    }
  }, [mode, startRecognition, startRecorder, supported, update])

  const clearError = useCallback(() => setError(null), [])

  // Leaving (or switching user) never leaves the microphone on.
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      discardRef.current = true
      transcriptionRef.current?.abort()
      if (recorderRef.current && recorderRef.current.state !== 'inactive') recorderRef.current.stop()
      recognitionRef.current?.abort()
      if (timerRef.current !== null) clearInterval(timerRef.current)
      streamRef.current?.getTracks().forEach((track) => track.stop())
    }
  }, [])

  return { supported, state, seconds, maxSeconds, heard, error, start, stop, cancel, clearError }
}
