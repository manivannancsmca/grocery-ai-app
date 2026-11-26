import { Icon } from '../../shared/icons'

/** @typedef {import('./api').KnowledgeSource} KnowledgeSource */

/**
 * @typedef {object} AnswerSourcesProps
 * @property {readonly KnowledgeSource[]} sources
 */

/**
 * The help-center articles an answer is based on ("From the help center: Refund policy · Delivery charges"), shown
 * under a complete answer so the reader knows it comes from the app's own policies, not from the model.
 * @param {AnswerSourcesProps} props
 */
export function AnswerSources({ sources }) {
  if (sources.length === 0) return null
  return (
    <p className="flex items-start gap-1.5 text-xs text-slate-500" data-testid="answer-sources">
      <Icon name="document" className="mt-0.5 size-3.5 shrink-0 text-slate-400" />
      <span>
        <span className="font-medium text-slate-600">From the help center:</span>{' '}
        {sources.map((source, index) => (
          <span key={source.documentId}>
            {index > 0 && <span aria-hidden="true"> · </span>}
            <span>{source.title}</span>
          </span>
        ))}
      </span>
    </p>
  )
}
