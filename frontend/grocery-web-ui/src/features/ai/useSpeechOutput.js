import { useCallback, useEffect, useRef, useState } from 'react'
import { synthesizeSpeech } from './api'
import {
  canSynthesizeSpeech,
  clipForSpeech,
  speechChunks,
  speechLanguage,
  toSpeechText,
  voiceErrorMessage,
} from './voice'

/** @typedef {import('./api').VoiceStatus} VoiceStatus */

/**
 * @typedef {object} SpeechOutput
 * @property {boolean} supported Answers can be read aloud here.
 * @property {string | null} speakingKey The message being read aloud.
 * @property {string | null} loadingKey The message whose audio is being prepared (`SERVER` mode).
 * @property {string | null} error
 * @property {(key: string, text: string) => void} speak
 * @property {() => void} stop
 * @property {() => void} clearError
 */

/**
 * Reads assistant answers aloud, one at a time: in `SERVER` mode the server's audio (`POST /api/ai/voice/speech`),
 * in `BROWSER` mode the browser's own speech synthesis. A new answer, `stop()` or unmounting stops the current one.
 * @param {VoiceStatus} status
 * @returns {SpeechOutput}
 */
export function useSpeechOutput(status) {
  const mode = status.mode
  const supported =
    (mode === 'SERVER' && status.speech && typeof Audio !== 'undefined') ||
    (mode === 'BROWSER' && canSynthesizeSpeech())

  const [speakingKey, setSpeakingKey] = useState(null)
  const [loadingKey, setLoadingKey] = useState(null)
  const [error, setError] = useState(null)

  /** Bumped by every speak / stop: callbacks of an older playback do nothing. */
  const playbackRef = useRef(0)
  const audioRef = useRef(null)
  const urlRef = useRef(null)
  const requestRef = useRef(null)
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
    (key, text, playback) => {
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
        .catch((failure) => {
          if (playback !== playbackRef.current || controller.signal.aborted || !mountedRef.current) return
          setLoadingKey(null)
          setError(voiceErrorMessage(failure))
        })
    },
    [halt, status.maxSpeechCharacters],
  )

  const speakWithBrowser = useCallback((key, text, playback) => {
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
    (key, text) => {
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
