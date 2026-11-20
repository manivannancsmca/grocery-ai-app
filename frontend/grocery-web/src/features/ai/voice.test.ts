import { describe, expect, it } from 'vitest'
import { ApiError } from '../../shared/api/problemDetail'
import { clipForSpeech, formatSeconds, microphoneErrorMessage, recognitionErrorMessage, speechChunks, toSpeechText, voiceErrorMessage } from './voice'

function voiceError(errorCode: string, extra: Record<string, unknown> = {}) {
  return new ApiError('refused', { kind: 'http', status: 400, problem: { errorCode, ...extra }, correlationId: null })
}

describe('voice helpers', () => {
  it('reads markdown as plain words, one sentence per line', () => {
    expect(toSpeechText('**112** vendors are approved.\n- [View vendors](/admin/vendors)')).toBe('112 vendors are approved. View vendors')
    expect(toSpeechText('## Low stock\n- Tomato – 3 left\n- Onion – 5 left')).toBe('Low stock. Tomato – 3 left. Onion – 5 left')
    expect(toSpeechText('Status `LOW_STOCK` stays as it is')).toBe('Status LOW_STOCK stays as it is')
    expect(toSpeechText(' \n ')).toBe('')
  })

  it('clips long answers after a whole sentence and splits them for the browser', () => {
    const text = 'One sentence here. Two sentence here. Three sentence here.'
    expect(clipForSpeech(text, 40)).toBe('One sentence here. Two sentence here.')
    expect(clipForSpeech(text, 500)).toBe(text)
    expect(speechChunks(text, 20)).toEqual(['One sentence here.', 'Two sentence here.', 'Three sentence here.'])
    expect(speechChunks('', 20)).toEqual([])
  })

  it('formats the recording time', () => {
    expect(formatSeconds(7)).toBe('0:07')
    expect(formatSeconds(65)).toBe('1:05')
    expect(formatSeconds(-3)).toBe('0:00')
  })

  it('explains every failure in plain words', () => {
    expect(microphoneErrorMessage(new DOMException('no', 'NotAllowedError'))).toMatch(/Microphone access is blocked/)
    expect(microphoneErrorMessage(new DOMException('no', 'NotFoundError'))).toMatch(/No microphone was found/)
    expect(recognitionErrorMessage('no-speech')).toMatch(/did not catch any words/)
    expect(recognitionErrorMessage('not-allowed')).toMatch(/Microphone access is blocked/)
    expect(voiceErrorMessage(voiceError('NO_SPEECH_DETECTED'))).toMatch(/did not catch any words/)
    expect(voiceErrorMessage(voiceError('UNSUPPORTED_AUDIO_FORMAT'))).toMatch(/could not be understood/)
    expect(voiceErrorMessage(voiceError('VOICE_RATE_LIMITED', { retryAfterSeconds: 600 }))).toMatch(/Try again in about 10 min/)
    expect(voiceErrorMessage(voiceError('VOICE_TIMEOUT'))).toMatch(/took too long/)
    expect(voiceErrorMessage(new Error('anything'))).toMatch(/Voice is not available right now/)
  })
})
