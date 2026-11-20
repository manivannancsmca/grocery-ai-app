#!/bin/sh
# Downloads the voice models into the persistent "speaches-models" volume (one-shot "speaches-init" container):
# the speech-to-text model (faster-whisper) and the text-to-speech model (Kokoro). Safe to re-run: a model that is
# already there answers 201 and is not downloaded again. Change them with AI_VOICE_TRANSCRIPTION_MODEL and
# AI_VOICE_SPEECH_MODEL in infrastructure/local/.env.
set -eu
URL="${SPEACHES_URL:-http://speaches:8000}"

for MODEL in "${TRANSCRIPTION_MODEL}" "${SPEECH_MODEL}"; do
  echo "Downloading ${MODEL} (the first run downloads a few hundred MB) ..."
  if ! curl -fsS -X POST "${URL}/v1/models/${MODEL}"; then
    echo "Could not download ${MODEL}. Check the model id (GET ${URL}/v1/registry lists them)." >&2
    exit 1
  fi
  echo
done

# Warm-up and self-test: speak a sentence, then transcribe that audio. Both models are loaded now (WHISPER__TTL=-1
# keeps them loaded), so the first question in the app does not wait for a cold load.
echo "Warming up: text to speech ..."
curl -fsS -X POST "${URL}/v1/audio/speech" -H "Content-Type: application/json" -o /tmp/warm-up.mp3 \
  -d "{\"model\":\"${SPEECH_MODEL}\",\"voice\":\"${SPEECH_VOICE:-af_heart}\",\"input\":\"Voice is ready.\",\"response_format\":\"mp3\"}" \
  || { echo "Text to speech failed." >&2; exit 1; }
echo "Warming up: speech to text ..."
curl -fsS -X POST "${URL}/v1/audio/transcriptions" -F "file=@/tmp/warm-up.mp3;type=audio/mpeg" \
  -F "model=${TRANSCRIPTION_MODEL}" -F "response_format=json" || { echo "Speech to text failed." >&2; exit 1; }
echo
echo "Voice ready."
