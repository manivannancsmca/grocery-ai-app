import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import { Alert, Button, Dialog, Spinner, Textarea } from '../../shared/components/ui'
import { AgentSwitcher } from './AgentSwitcher'
import { AI_LIMITS } from './api'
import { useAiStatus } from './hooks'
import { MicButton, VoiceStatusLine } from './VoiceControls'
import { ConversationList } from './ConversationList'
import { MessageView } from './AssistantMessage'

/** @typedef {import('./api').AgentView} AgentView */
/** @typedef {import('./useAssistantChat').AssistantChat} AssistantChat */
/** @typedef {import('react').RefObject} RefObject */
/** @typedef {import('./useSpeechOutput').SpeechOutput} SpeechOutput */
/** @typedef {import('./useVoiceInput').VoiceInput} VoiceInput */

/**
 * Voice for the panel (owned by the session, so it outlives closing the panel).
 * @typedef {object} AssistantVoice
 * @property {VoiceInput} input
 * @property {SpeechOutput} speech
 * @property {boolean} draftSpoken The input holds words the microphone heard.
 * @property {RefObject<HTMLTextAreaElement | null>} inputRef The chat input, focused when spoken words arrive.
 */

/**
 * @typedef {object} AssistantPanelProps
 * @property {AgentView} agent
 * @property {boolean} open
 * @property {() => void} onClose
 * @property {AssistantChat} chat
 * @property {string} draft
 * @property {(value: string) => void} onDraftChange
 * @property {string} approvalsPath Customers: their approvals page; admins: AI approvals.
 * @property {string} approvalsLabel
 * @property {readonly AgentView[]} [otherAgents] Phase 12: other agents the user may switch to (a delivery manager has the business and the delivery assistant).
 * @property {(agentId: string) => void} [onSwitchAgent]
 * @property {AssistantVoice} [voice] Speak questions and hear answers (absent: no voice controls).
 */

/**
 * The assistant slide-over (Phase 11 §9): the conversation with streamed answers, tool chips and approval cards,
 * the list of stored conversations (open, rename, delete), example prompts, voice input and read-aloud answers, and an
 * offline state from `GET /api/ai/status`. The answer keeps streaming while the panel is closed.
 * @param {AssistantPanelProps} props
 */
export function AssistantPanel({
  agent,
  open,
  onClose,
  chat,
  draft,
  onDraftChange,
  approvalsPath,
  approvalsLabel,
  otherAgents = [],
  onSwitchAgent,
  voice,
}) {
  const [view, setView] = useState('chat')
  const status = useAiStatus(open)
  const inputId = useId()
  const hintId = useId()
  const endRef = useRef(null)
  const offline = status.data?.available === false || (status.isError && !status.data)
  const empty = chat.messages.length === 0 && !chat.loading && !chat.loadError
  /** While the microphone listens or its words are transcribed, sending waits. */
  const voiceBusy = voice !== undefined && voice.input.state !== 'idle'

  useEffect(() => {
    if (open && view === 'chat') endRef.current?.scrollIntoView?.({ block: 'end' })
  }, [chat.messages, open, view])

  function send(text, spoken = false) {
    if (!text.trim() || chat.streaming || offline || voiceBusy) return
    chat.send(text, { voice: spoken })
    onDraftChange('')
  }

  function sendDraft() {
    send(draft, voice?.draftSpoken ?? false)
  }

  function onKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      sendDraft()
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      variant="drawer"
      icon="sparkle"
      title={agent.name}
      description={agent.description}
      footer={
        view === 'chat' ? (
          <form
            className="w-full space-y-1.5"
            onSubmit={(event) => {
              event.preventDefault()
              sendDraft()
            }}
          >
            <label htmlFor={inputId} className="sr-only">
              Message the assistant
            </label>
            <div className="flex items-end gap-2">
              <Textarea
                id={inputId}
                ref={voice?.inputRef}
                rows={2}
                value={draft}
                maxLength={AI_LIMITS.message}
                disabled={offline}
                aria-describedby={hintId}
                placeholder={
                  offline
                    ? 'The assistant is offline'
                    : voice?.input.supported
                      ? 'Ask something, or press the microphone…'
                      : 'Ask something…'
                }
                onChange={(event) => onDraftChange(event.target.value)}
                onKeyDown={onKeyDown}
                className="resize-none"
              />
              {voice && <MicButton voice={voice.input} disabled={offline || chat.streaming} />}
              {chat.streaming ? (
                <Button variant="secondary" onClick={chat.cancel}>
                  Stop
                </Button>
              ) : (
                <Button type="submit" disabled={offline || voiceBusy || draft.trim().length === 0}>
                  Send
                </Button>
              )}
            </div>
            {voice && (voice.input.supported || voice.speech.supported) && (
              <VoiceStatusLine voice={voice.input} speech={voice.speech} />
            )}
            <p id={hintId} className="flex justify-between gap-2 text-xs text-slate-500">
              <span>
                The assistant can make mistakes. Orders, cancellations, refunds, stock, prices and reassignments always
                need a person’s approval.
              </span>
              {draft.length > AI_LIMITS.message - 200 && (
                <span className="shrink-0 tabular-nums">
                  {draft.length}/{AI_LIMITS.message}
                </span>
              )}
            </p>
          </form>
        ) : undefined
      }
    >
      <div className="space-y-4">
        {onSwitchAgent && otherAgents.length > 0 && (
          <AgentSwitcher current={agent} others={otherAgents} disabled={chat.streaming} onSwitch={onSwitchAgent} />
        )}
        <div className="flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            variant="secondary"
            onClick={() => {
              chat.startNew()
              setView('chat')
            }}
            disabled={chat.streaming || (chat.conversationId === null && chat.messages.length === 0)}
          >
            New conversation
          </Button>
          <Button
            size="sm"
            variant={view === 'conversations' ? 'primary' : 'ghost'}
            aria-pressed={view === 'conversations'}
            onClick={() => setView(view === 'chat' ? 'conversations' : 'chat')}
          >
            Conversations
          </Button>
          <Link
            to={approvalsPath}
            onClick={onClose}
            className="ml-auto text-sm font-medium text-emerald-700 hover:text-emerald-800"
          >
            {approvalsLabel} →
          </Link>
        </div>

        {offline && (
          <Alert
            tone="warning"
            role="status"
            title="The assistant is offline"
            actions={
              <Button size="sm" variant="secondary" onClick={() => void status.refetch()} disabled={status.isFetching}>
                Check again
              </Button>
            }
          >
            The AI model cannot be reached right now. Everything else — shopping, your cart, orders and approvals —
            works as usual.
          </Alert>
        )}

        {view === 'conversations' ? (
          <ConversationList
            agent={agent.id}
            currentId={chat.conversationId}
            onOpen={(id) => {
              chat.open(id)
              setView('chat')
            }}
            onNew={() => {
              chat.startNew()
              setView('chat')
            }}
            onDeleted={() => chat.startNew()}
          />
        ) : (
          <div role="log" aria-label="Conversation" aria-busy={chat.streaming || chat.loading} className="space-y-4">
            {chat.loading && (
              <div className="flex justify-center py-8">
                <Spinner label="Loading the conversation" />
              </div>
            )}
            {chat.loadError && (
              <Alert tone="danger" role="alert">
                {chat.loadError}
              </Alert>
            )}
            {empty && (
              <div className="space-y-3 rounded-xl bg-slate-50 p-4">
                <p className="text-sm text-slate-700">
                  Hi! Ask me in your own words. I can look things up and prepare actions; you decide what runs.
                </p>
                {agent.examplePrompts.length > 0 && (
                  <div>
                    <p className="mb-1.5 text-xs font-semibold tracking-wide text-slate-500 uppercase">Try</p>
                    <ul aria-label="Example prompts" className="flex flex-wrap gap-2">
                      {agent.examplePrompts.map((prompt) => (
                        <li key={prompt}>
                          <button
                            type="button"
                            onClick={() => send(prompt)}
                            disabled={offline}
                            className="rounded-full border border-slate-200 bg-white px-3 py-1 text-left text-sm text-slate-700 shadow-xs hover:border-emerald-300 disabled:opacity-50"
                          >
                            {prompt}
                          </button>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </div>
            )}
            {chat.messages.map((message) => (
              <MessageView
                key={message.key}
                message={message}
                agent={agent.id}
                onNavigate={onClose}
                speech={voice?.speech}
              />
            ))}
            <div ref={endRef} />
          </div>
        )}
      </div>
    </Dialog>
  )
}
