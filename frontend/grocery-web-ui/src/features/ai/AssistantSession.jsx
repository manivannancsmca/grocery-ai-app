import { useCallback, useEffect, useRef, useState } from 'react'
import { VOICE_OFF } from './api'
import { AssistantPanel } from './AssistantPanel'
import { useConversations, useVoiceStatus } from './hooks'
import { AGENT_APPROVALS_PAGE } from './labels'
import { useAssistantChat } from './useAssistantChat'
import { useSpeechOutput } from './useSpeechOutput'
import { useVoiceInput } from './useVoiceInput'

/** @typedef {import('./api').AgentView} AgentView */

/**
 * @typedef {object} AssistantSessionProps
 * @property {AgentView} agent
 * @property {boolean} admin
 * @property {readonly AgentView[]} [otherAgents] Phase 12: other agents the user may switch to (empty for most users).
 * @property {(agentId: string) => void} [onSwitchAgent]
 * @property {boolean} open
 * @property {() => void} onClose
 * @property {(streaming: boolean) => void} onStreamingChange Tells the launcher whether an answer is being written (it shows a pulse).
 */

/**
 * One user's assistant conversation and its panel. Loaded (lazily, in its own chunk) the first time the panel is
 * opened, then kept mounted, so the conversation — and an answer still streaming — survive closing the panel.
 *
 * - History: conversations are stored by the server per user. When the session starts (the first time the panel is
 *   opened after a page load, a refresh or a new sign-in) the user's most recent conversation with this assistant is
 *   opened again; "New conversation" starts another one, and the list holds all of them.
 * - Voice: the microphone fills the input (the user reviews and sends); the answer to a spoken question is read aloud
 *   when it is complete, and any answer can be read aloud on request. Closing the panel turns the microphone off.
 * @param {AssistantSessionProps} props
 */
export default function AssistantSession({
  agent,
  admin,
  otherAgents,
  onSwitchAgent,
  open,
  onClose,
  onStreamingChange,
}) {
  const voiceStatus = useVoiceStatus(true)
  const status = voiceStatus.data ?? VOICE_OFF
  const speech = useSpeechOutput(status)

  const onAnswerDone = useCallback(
    (answer) => {
      if (answer.spoken && speech.supported && answer.content.trim()) speech.speak(answer.key, answer.content)
    },
    [speech],
  )
  const chat = useAssistantChat(agent.id, { onAnswerDone })

  const [draft, setDraft] = useState('')
  /** The input holds words the microphone heard: the question counts as spoken when it is sent. */
  const [draftSpoken, setDraftSpoken] = useState(false)
  const inputRef = useRef(null)

  const changeDraft = useCallback((value) => {
    setDraft(value)
    if (value.trim() === '') setDraftSpoken(false)
  }, [])

  const voiceInput = useVoiceInput(status, {
    onText: (text) => {
      setDraft((current) => (current.trim() ? `${current.trimEnd()} ${text}` : text))
      setDraftSpoken(true)
      inputRef.current?.focus()
    },
    onStart: speech.stop,
  })

  // Closing the panel never leaves the microphone listening.
  const { cancel: cancelRecording } = voiceInput
  useEffect(() => {
    if (!open) cancelRecording()
  }, [open, cancelRecording])

  // Restore the latest stored conversation once per session (the list is the same query the "Conversations" view uses).
  const conversations = useConversations(agent.id, true)
  const resumedRef = useRef(false)
  const { open: openConversation, conversationId, messages, streaming } = chat
  useEffect(() => {
    if (resumedRef.current || !conversations.data) return
    resumedRef.current = true
    const latest = conversations.data.content[0]
    if (latest && conversationId === null && messages.length === 0 && !streaming) openConversation(latest.id)
  }, [conversations.data, conversationId, messages.length, streaming, openConversation])

  // Each agent's own approvals page (Phase 12 §6); unknown agents fall back to the area's page.
  const approvals =
    AGENT_APPROVALS_PAGE[agent.id] ?? (admin ? AGENT_APPROVALS_PAGE['admin-bi'] : AGENT_APPROVALS_PAGE.shopping)

  useEffect(() => {
    onStreamingChange(chat.streaming)
  }, [chat.streaming, onStreamingChange])

  const voice = { input: voiceInput, speech, draftSpoken, inputRef }

  return (
    <AssistantPanel
      agent={agent}
      open={open}
      onClose={onClose}
      chat={chat}
      draft={draft}
      onDraftChange={changeDraft}
      approvalsPath={approvals?.path ?? '/assistant/approvals'}
      approvalsLabel={approvals?.label ?? 'Assistant actions'}
      otherAgents={otherAgents}
      onSwitchAgent={onSwitchAgent}
      voice={voice}
    />
  )
}
