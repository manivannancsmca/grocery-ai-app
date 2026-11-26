import { type FormEvent, type KeyboardEvent, type RefObject, useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import { errorMessage } from '../../shared/api/problemDetail'
import { Alert, Button, ConfirmDialog, Dialog, EmptyState, Input, Spinner, Textarea } from '../../shared/components/ui'
import { useNow } from '../../shared/hooks/useNow'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDateTime, formatRelativeTime } from '../../shared/utils/format'
import { AgentSwitcher } from './AgentSwitcher'
import { AI_LIMITS, type AgentView, type ConversationSummary } from './api'
import { AnswerLinks } from './AnswerLinks'
import { AnswerSources } from './AnswerSources'
import { ApprovalCard } from './ApprovalCard'
import { FormattedText } from './FormattedText'
import { useAiStatus, useConversations, useDeleteConversation, useRenameConversation } from './hooks'
import { answerLinks, isAgentPage } from './pageLinks'
import { ToolChips } from './ToolChips'
import type { AssistantChat, ChatMessage } from './useAssistantChat'
import type { SpeechOutput } from './useSpeechOutput'
import type { VoiceInput } from './useVoiceInput'
import { ListenButton, MicButton, VoiceStatusLine } from './VoiceControls'

/** Voice for the panel (owned by the session, so it outlives closing the panel). */
export interface AssistantVoice {
  input: VoiceInput
  speech: SpeechOutput
  /** The input holds words the microphone heard. */
  draftSpoken: boolean
  /** The chat input, focused when spoken words arrive. */
  inputRef: RefObject<HTMLTextAreaElement | null>
}

/** Longest conversation title (the server's limit). */
const TITLE_LIMIT = 80

function TypingIndicator() {
  return (
    <p className="flex items-center gap-1 py-1 text-slate-400" aria-label="The assistant is writing">
      <span className="size-1.5 animate-bounce rounded-full bg-current [animation-delay:-0.3s]" />
      <span className="size-1.5 animate-bounce rounded-full bg-current [animation-delay:-0.15s]" />
      <span className="size-1.5 animate-bounce rounded-full bg-current" />
    </p>
  )
}

interface MessageViewProps {
  message: ChatMessage
  /** The assistant answering: decides which pages its answers may link to. */
  agent: string
  /** Following a link to a page closes the panel, so the page is visible. */
  onNavigate: () => void
  /** Reads finished answers aloud (absent: no voice). */
  speech?: SpeechOutput
}

function MessageView({ message, agent, onNavigate, speech }: MessageViewProps) {
  if (message.role === 'USER') {
    return (
      <div className="flex flex-col items-end gap-0.5">
        <div className="max-w-[85%] rounded-2xl rounded-br-md bg-emerald-600 px-3.5 py-2 text-sm break-words whitespace-pre-wrap text-white">
          <span className="sr-only">{message.spoken ? 'You (spoken): ' : 'You: '}</span>
          {message.content}
        </div>
        {message.spoken && (
          <span aria-hidden="true" className="flex items-center gap-1 text-[11px] text-slate-400">
            <Icon name="mic" className="size-3" />
            Spoken
          </span>
        )}
      </div>
    )
  }
  const writing = message.status === 'streaming'
  // The tools' pages appear once the answer is complete (the text may still link to some of them itself).
  const links = writing ? [] : answerLinks(agent, message.tools, message.content)
  return (
    <div className="flex gap-2.5" data-testid="assistant-message">
      <span className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full bg-emerald-50 text-emerald-700 ring-1 ring-emerald-200 ring-inset">
        <Icon name="sparkle" className="size-4" />
      </span>
      <div className="min-w-0 flex-1 space-y-2">
        <span className="sr-only">Assistant:</span>
        <ToolChips tools={message.tools} />
        {message.content ? (
          <FormattedText text={message.content} links={{ allow: (path) => isAgentPage(agent, path), onNavigate }} />
        ) : writing ? (
          <>
            <TypingIndicator />
            {message.checking && <p className="text-xs text-slate-500">{message.checking}</p>}
          </>
        ) : null}
        {message.notice && !writing && (
          <p className="flex items-start gap-1.5 rounded-lg bg-amber-50 px-2.5 py-1.5 text-xs text-amber-900 ring-1 ring-amber-200 ring-inset">
            <Icon name="warning" className="mt-0.5 size-3.5 shrink-0" />
            <span>{message.notice.message}</span>
          </p>
        )}
        {!writing && <AnswerSources sources={message.sources ?? []} />}
        <AnswerLinks links={links} onNavigate={onNavigate} />
        {speech && message.status === 'done' && message.content && <ListenButton messageKey={message.key} text={message.content} speech={speech} />}
        {message.approvals.map((approval) => (
          <ApprovalCard key={approval.id} approval={approval} compact />
        ))}
        {message.status === 'error' && message.error && (
          <Alert tone="warning" role="alert">
            {message.error.message}
          </Alert>
        )}
        {message.status === 'cancelled' && <p className="text-xs text-slate-500 italic">Stopped.</p>}
        {message.status === 'interrupted' && (
          <p className="text-xs text-amber-700">The answer was cut off (the connection dropped). Ask again to continue.</p>
        )}
      </div>
    </div>
  )
}

/** Renames one conversation in place: Enter or "Save" keeps the new title, Escape or "Cancel" keeps the old one. */
function RenameForm({ conversation, onDone }: { conversation: ConversationSummary; onDone: () => void }) {
  const rename = useRenameConversation()
  const [title, setTitle] = useState(conversation.title)
  const [error, setError] = useState<string | null>(null)
  const inputId = useId()
  const errorId = useId()
  const clean = title.replace(/\s+/g, ' ').trim()

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!clean) {
      setError('Enter a name for the conversation.')
      return
    }
    if (clean === conversation.title) {
      onDone()
      return
    }
    setError(null)
    try {
      await rename.mutateAsync({ id: conversation.id, title: clean })
      onDone()
    } catch (failure) {
      setError(errorMessage(failure))
    }
  }

  return (
    <form onSubmit={(event) => void save(event)} className="flex min-w-0 flex-1 flex-col gap-1.5">
      <label htmlFor={inputId} className="sr-only">
        New name for “{conversation.title}”
      </label>
      <Input
        id={inputId}
        value={title}
        maxLength={TITLE_LIMIT}
        autoFocus
        invalid={error !== null}
        aria-describedby={error ? errorId : undefined}
        onChange={(event) => setTitle(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.preventDefault()
            event.stopPropagation()
            onDone()
          }
        }}
      />
      {error && (
        <p id={errorId} role="alert" className="text-xs text-red-700">
          {error}
        </p>
      )}
      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={rename.isPending}>
          Save
        </Button>
        <Button size="sm" variant="ghost" onClick={onDone} disabled={rename.isPending}>
          Cancel
        </Button>
      </div>
    </form>
  )
}

interface ConversationListProps {
  agent: string
  currentId: string | null
  onOpen: (id: string) => void
  onNew: () => void
  /** A deleted conversation that was open is left. */
  onDeleted: (id: string) => void
}

function ConversationList({ agent, currentId, onOpen, onNew, onDeleted }: ConversationListProps) {
  const list = useConversations(agent, true)
  const remove = useDeleteConversation()
  const now = useNow(60_000)
  const [toDelete, setToDelete] = useState<ConversationSummary | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [renamingId, setRenamingId] = useState<string | null>(null)

  async function confirmDelete() {
    if (!toDelete) return
    setDeleteError(null)
    try {
      await remove.mutateAsync(toDelete.id)
      if (toDelete.id === currentId) onDeleted(toDelete.id)
      setToDelete(null)
    } catch (error) {
      setDeleteError(errorMessage(error))
    }
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-slate-900">Your conversations</h3>
        <Button size="sm" variant="secondary" onClick={onNew}>
          New conversation
        </Button>
      </div>
      {list.isPending ? (
        <div className="flex justify-center py-8">
          <Spinner label="Loading conversations" />
        </div>
      ) : list.isError ? (
        <Alert tone="danger" role="alert" actions={<Button size="sm" variant="secondary" onClick={() => void list.refetch()}>Try again</Button>}>
          Could not load your conversations: {list.error.message}
        </Alert>
      ) : list.data.content.length === 0 ? (
        <EmptyState title="No conversations yet" description="Ask the assistant something; your conversations are kept here." />
      ) : (
        <ul aria-label="Conversations" className="divide-y divide-slate-100 rounded-xl border border-slate-200">
          {list.data.content.map((conversation) => (
            <li key={conversation.id} className={cn('flex items-center gap-2 px-3 py-2', conversation.id === currentId && 'bg-emerald-50/60')}>
              {renamingId === conversation.id ? (
                <RenameForm conversation={conversation} onDone={() => setRenamingId(null)} />
              ) : (
                <>
                  <button
                    type="button"
                    onClick={() => onOpen(conversation.id)}
                    aria-current={conversation.id === currentId ? 'true' : undefined}
                    className="min-w-0 flex-1 rounded-lg text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
                  >
                    <span className="block truncate text-sm font-medium text-slate-900">{conversation.title || 'Untitled conversation'}</span>
                    <span className="block text-xs text-slate-500">
                      <time dateTime={conversation.updatedAt} title={formatDateTime(conversation.updatedAt)}>
                        {formatRelativeTime(conversation.updatedAt, now)}
                      </time>
                    </span>
                  </button>
                  <button
                    type="button"
                    onClick={() => setRenamingId(conversation.id)}
                    aria-label={`Rename conversation “${conversation.title}”`}
                    className="shrink-0 rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
                  >
                    <Icon name="pencil" className="size-4" />
                  </button>
                  <button
                    type="button"
                    onClick={() => setToDelete(conversation)}
                    aria-label={`Delete conversation “${conversation.title}”`}
                    className="shrink-0 rounded-lg p-1.5 text-slate-400 hover:bg-red-50 hover:text-red-600"
                  >
                    <Icon name="trash" className="size-4" />
                  </button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={toDelete !== null}
        title="Delete this conversation?"
        confirmLabel="Delete"
        cancelLabel="Keep it"
        tone="danger"
        pending={remove.isPending}
        error={deleteError}
        onConfirm={() => void confirmDelete()}
        onClose={() => {
          setToDelete(null)
          setDeleteError(null)
        }}
      >
        <p>
          “{toDelete?.title}” and its messages are deleted. Approval requests made in it and the audit log are kept.
        </p>
      </ConfirmDialog>
    </div>
  )
}

interface AssistantPanelProps {
  agent: AgentView
  open: boolean
  onClose: () => void
  chat: AssistantChat
  draft: string
  onDraftChange: (value: string) => void
  /** Customers: their approvals page; admins: AI approvals. */
  approvalsPath: string
  approvalsLabel: string
  /** Phase 12: other agents the user may switch to (a delivery manager has the business and the delivery assistant). */
  otherAgents?: readonly AgentView[]
  onSwitchAgent?: (agentId: string) => void
  /** Speak questions and hear answers (absent: no voice controls). */
  voice?: AssistantVoice
}

/**
 * The assistant slide-over (Phase 11 §9): the conversation with streamed answers, tool chips and approval cards,
 * the list of stored conversations (open, rename, delete), example prompts, voice input and read-aloud answers, and an
 * offline state from `GET /api/ai/status`. The answer keeps streaming while the panel is closed.
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
}: AssistantPanelProps) {
  const [view, setView] = useState<'chat' | 'conversations'>('chat')
  const status = useAiStatus(open)
  const inputId = useId()
  const hintId = useId()
  const endRef = useRef<HTMLDivElement>(null)
  const offline = status.data?.available === false || (status.isError && !status.data)
  const empty = chat.messages.length === 0 && !chat.loading && !chat.loadError
  /** While the microphone listens or its words are transcribed, sending waits. */
  const voiceBusy = voice !== undefined && voice.input.state !== 'idle'

  useEffect(() => {
    if (open && view === 'chat') endRef.current?.scrollIntoView?.({ block: 'end' })
  }, [chat.messages, open, view])

  function send(text: string, spoken = false) {
    if (!text.trim() || chat.streaming || offline || voiceBusy) return
    chat.send(text, { voice: spoken })
    onDraftChange('')
  }

  function sendDraft() {
    send(draft, voice?.draftSpoken ?? false)
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
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
                placeholder={offline ? 'The assistant is offline' : voice?.input.supported ? 'Ask something, or press the microphone…' : 'Ask something…'}
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
            {voice && (voice.input.supported || voice.speech.supported) && <VoiceStatusLine voice={voice.input} speech={voice.speech} />}
            <p id={hintId} className="flex justify-between gap-2 text-xs text-slate-500">
              <span>The assistant can make mistakes. Orders, cancellations, refunds, stock, prices and reassignments always need a person’s approval.</span>
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
        {onSwitchAgent && otherAgents.length > 0 && <AgentSwitcher current={agent} others={otherAgents} disabled={chat.streaming} onSwitch={onSwitchAgent} />}
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
          <Button size="sm" variant={view === 'conversations' ? 'primary' : 'ghost'} aria-pressed={view === 'conversations'} onClick={() => setView(view === 'chat' ? 'conversations' : 'chat')}>
            Conversations
          </Button>
          <Link to={approvalsPath} onClick={onClose} className="ml-auto text-sm font-medium text-emerald-700 hover:text-emerald-800">
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
            The AI model cannot be reached right now. Everything else — shopping, your cart, orders and approvals — works as usual.
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
                <p className="text-sm text-slate-700">Hi! Ask me in your own words. I can look things up and prepare actions; you decide what runs.</p>
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
              <MessageView key={message.key} message={message} agent={agent.id} onNavigate={onClose} speech={voice?.speech} />
            ))}
            <div ref={endRef} />
          </div>
        )}
      </div>
    </Dialog>
  )
}
