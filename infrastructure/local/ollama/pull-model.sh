#!/bin/sh
# Pulls the dev LLM and the knowledge base's embedding model into the persistent "ollama-models" volume (one-shot
# "ollama-init" container). Change them with OLLAMA_MODEL / OLLAMA_EMBEDDING_MODEL in infrastructure/local/.env;
# OLLAMA_EMBEDDING_MODEL= (empty) skips the embedding model (docs/rag/RAG-LOCAL-SETUP.md).
set -eu
MODEL="${OLLAMA_MODEL:-qwen3:4b-instruct}"   # a non-thinking instruct model: thinking variants put their reasoning in the answer
EMBEDDING_MODEL="${OLLAMA_EMBEDDING_MODEL-nomic-embed-text}"   # the knowledge base's (RAG) embeddings, ~0.3 GB
export OLLAMA_HOST="${OLLAMA_HOST:-ollama:11434}"

echo "Waiting for Ollama at ${OLLAMA_HOST} ..."
until ollama list >/dev/null 2>&1; do sleep 2; done

echo "Pulling ${MODEL} (first run downloads a few GB) ..."
ollama pull "${MODEL}"
if [ -n "${EMBEDDING_MODEL}" ]; then
  echo "Pulling the embedding model ${EMBEDDING_MODEL} ..."
  ollama pull "${EMBEDDING_MODEL}"
fi
ollama list

# Load it into memory now, so the first chat does not pay the load (on CPU a cold first answer can exceed the
# assistant's model timeout). OLLAMA_KEEP_ALIVE on the ollama service keeps it loaded while it is used.
# (An embedding model cannot answer "ollama run"; it loads on its first use, in a second or two.)
echo "Warming up ${MODEL} ..."
ollama run "${MODEL}" "Reply with OK." >/dev/null 2>&1 || echo "Warm-up failed (the model loads on first use instead)."
echo "Ready."
