import { useCallback, useEffect, useRef, useState } from 'react'
import { synthesizeSpeech, type VoiceStatus } from './api'
import { canSynthesizeSpeech, clipForSpeech, speechChunks, speechLanguage, toSpeechText, voiceErrorMessage } from './voice'

export interface SpeechOutput {
  /** Answers can be read aloud here. */
  supported: boolean
  /** The message being read aloud. */
  speakingKey: string | null
  /** The message whose audio is being prepared (`SERVER` mode). */
  loadingKey: string | null
  error: string | null
  speak: (key: string, text: string) => void
  stop: () => void
  clearError: () => void
}

/**
 * Reads assistant answers aloud, one at a time: in `SERVER` mode the server's audio (`POST /api/ai/voice/speech`),
 * in `BROWSER` mode the browser's own speech synthesis. A new answer, `stop()` or unmounting stops the current one.
 */
export function useSpeechOutput(status: VoiceStatus): SpeechOutput {
  const mode = status.mode
  const supported = (mode === 'SERVER' && status.speech && typeof Audio !== 'undefined') || (mode === 'BROWSER' && canSynthesizeSpeech())

  const [speakingKey, setSpeakingKey] = useState<string | null>(null)
  const [loadingKey, setLoadingKey] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  /** Bumped by every speak / stop: callbacks of an older playback do nothing. */
  const playbackRef = useRef(0)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const urlRef = useRef<string | null>(null)
  const requestRef = useRef<AbortController | null>(null)
  const mountedRef = useRef(true)

  const halt = useCallback(() => {
    playbackRef.current += 1
    requestRef.current?.abort()
    requestRef.current = null
    if (audioRef.current) {
      audioRef.current.onended = null
      audioRef.current.onerror = null
      audioRef.current.pause()
      audioRef.current = null
    }
    if (urlRef.current) {
      URL.revokeObjectURL(urlRef.current)
      urlRef.current = null
    }
    if (canSynthesizeSpeech()) window.speechSynthesis.cancel()
  }, [])

  const stop = useCallback(() => {
    halt()
    setSpeakingKey(null)
    setLoadingKey(null)
  }, [halt])

  const speakWithServer = useCallback(
    (key: string, text: string, playback: number) => {
      const controller = new AbortController()
      requestRef.current = controller
      setLoadingKey(key)
      synthesizeSpeech(clipForSpeech(text, status.maxSpeechCharacters), controller.signal)
        .then(async (audioBlob) => {
          if (playback !== playbackRef.current || !mountedRef.current) return
          const url = URL.createObjectURL(audioBlob)
          const audio = new Audio(url)
          urlRef.current = url
          audioRef.current = audio
          const finish = () => {
            if (playback !== playbackRef.current) return
            halt()
            setSpeakingKey(null)
          }
          audio.onended = finish
          audio.onerror = () => {
            finish()
            setError('The answer could not be played.')
          }
          setLoadingKey(null)
          setSpeakingKey(key)
          try {
            await audio.play()
          } catch {
            if (playback !== playbackRef.current) return
            halt()
            setSpeakingKey(null)
            setError('Your browser blocked the sound. Press Listen to hear the answer.')
          }
        })
        .catch((failure: unknown) => {
          if (playback !== playbackRef.current || controller.signal.aborted || !mountedRef.current) return
          setLoadingKey(null)
          setError(voiceErrorMessage(failure))
        })
    },
    [halt, status.maxSpeechCharacters],
  )

  const speakWithBrowser = useCallback((key: string, text: string, playback: number) => {
    const chunks = speechChunks(toSpeechText(text))
    if (chunks.length === 0) return
    const language = speechLanguage()
    chunks.forEach((chunk, index) => {
      const utterance = new SpeechSynthesisUtterance(chunk)
      utterance.lang = language
      if (index === chunks.length - 1) {
        utterance.onend = () => {
          if (playback === playbackRef.current && mountedRef.current) setSpeakingKey(null)
        }
      }
      utterance.onerror = (event) => {
        if (playback !== playbackRef.current || !mountedRef.current) return
        setSpeakingKey(null)
        if (event.error !== 'interrupted' && event.error !== 'canceled') setError('The answer could not be read aloud.')
      }
      window.speechSynthesis.speak(utterance)
    })
    setSpeakingKey(key)
  }, [])

  const speak = useCallback(
    (key: string, text: string) => {
      if (!supported || !text.trim()) return
      halt()
      setError(null)
      setSpeakingKey(null)
      setLoadingKey(null)
      const playback = playbackRef.current
      if (mode === 'SERVER') speakWithServer(key, text, playback)
      else speakWithBrowser(key, text, playback)
    },
    [halt, mode, speakWithBrowser, speakWithServer, supported],
  )

  const clearError = useCallback(() => setError(null), [])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      halt()
    }
  }, [halt])

  return { supported, speakingKey, loadingKey, error, speak, stop, clearError }
}
