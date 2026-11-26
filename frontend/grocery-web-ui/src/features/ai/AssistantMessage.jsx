/** One message of the assistant chat (the user's question or the assistant's answer with its tool chips, approval cards and links), and the typing dots. */

import { Alert } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { AnswerLinks } from './AnswerLinks'
import { AnswerSources } from './AnswerSources'
import { ApprovalCard } from './ApprovalCard'
import { FormattedText } from './FormattedText'
import { answerLinks, isAgentPage } from './pageLinks'
import { ToolChips } from './ToolChips'
import { ListenButton } from './VoiceControls'

/** @typedef {import('./useAssistantChat').ChatMessage} ChatMessage */
/** @typedef {import('./useSpeechOutput').SpeechOutput} SpeechOutput */

function TypingIndicator() {
  return (
    <p className="flex items-center gap-1 py-1 text-slate-400" aria-label="The assistant is writing">
      <span className="size-1.5 animate-bounce rounded-full bg-current [animation-delay:-0.3s]" />
      <span className="size-1.5 animate-bounce rounded-full bg-current [animation-delay:-0.15s]" />
      <span className="size-1.5 animate-bounce rounded-full bg-current" />
    </p>
  )
}

/**
 * @typedef {object} MessageViewProps
 * @property {ChatMessage} message
 * @property {string} agent The assistant answering: decides which pages its answers may link to.
 * @property {() => void} onNavigate Following a link to a page closes the panel, so the page is visible.
 * @property {SpeechOutput} [speech] Reads finished answers aloud (absent: no voice).
 */

/** @param {MessageViewProps} props */
export function MessageView({ message, agent, onNavigate, speech }) {
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
        {speech && message.status === 'done' && message.content && (
          <ListenButton messageKey={message.key} text={message.content} speech={speech} />
        )}
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
          <p className="text-xs text-amber-700">
            The answer was cut off (the connection dropped). Ask again to continue.
          </p>
        )}
      </div>
    </div>
  )
}
