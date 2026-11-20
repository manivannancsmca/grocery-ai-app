import { useState } from 'react'
import { errorMessage } from '../../shared/api/problemDetail'
import { Alert, Button, Dialog, SelectField, Spinner } from '../../shared/components/ui'
import { DEAD_LETTER_LIMITS, DEAD_LETTER_SERVICES, isDeadLetterService } from './api'
import { useReplayAllDeadLetters } from './hooks'
import { serviceName } from './labels'

/** @typedef {import('./api').DeadLetterService} DeadLetterService */

/**
 * @typedef {object} ReplayAllDialogProps
 * @property {boolean} open
 * @property {DeadLetterService} initialService Preselected service (the service filter, or one with open dead letters).
 * @property {string} eventType The current event type / consumer group filters; replay-all is narrowed to them.
 * @property {string} consumerGroup
 * @property {() => void} onClose
 */

/**
 * `POST /api/admin/ops/<service>/dead-letters/replay-all`: one service's PENDING and REPLAY_FAILED dead letters,
 * oldest first, at most 100 per run — then shows `{attempted, replayed, failed}`.
 * @param {ReplayAllDialogProps} props
 */
export function ReplayAllDialog(props) {
  if (!props.open) return null
  return <ReplayAllForm {...props} />
}

/** @param {ReplayAllDialogProps} props */
function ReplayAllForm({ initialService, eventType, consumerGroup, onClose }) {
  const replayAll = useReplayAllDeadLetters()
  const [service, setService] = useState(initialService)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const narrowed = [eventType && `event type ${eventType}`, consumerGroup && `consumer group ${consumerGroup}`]
    .filter(Boolean)
    .join(' and ')

  async function run() {
    setError(null)
    setResult(null)
    try {
      const outcome = await replayAll.mutateAsync({
        service,
        body: { eventType: eventType || undefined, consumerGroup: consumerGroup || undefined },
      })
      setResult({ service, outcome })
    } catch (failure) {
      setError(`Replay all failed: ${errorMessage(failure)}`)
    }
  }

  const running = replayAll.isPending

  return (
    <Dialog
      open
      onClose={onClose}
      dismissible={!running}
      size="sm"
      title="Replay all open dead letters"
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={running}>
            {result ? 'Done' : 'Cancel'}
          </Button>
          <Button onClick={() => void run()} disabled={running}>
            {running && <Spinner size="sm" className="[&_svg]:text-white" />}
            {result ? 'Run again' : 'Replay all'}
          </Button>
        </>
      }
    >
      <div className="space-y-4 text-sm text-slate-600">
        <SelectField
          label="Service"
          value={service}
          disabled={running}
          onChange={(event) => {
            if (isDeadLetterService(event.target.value)) setService(event.target.value)
            setResult(null)
          }}
        >
          {DEAD_LETTER_SERVICES.map((value) => (
            <option key={value} value={value}>
              {serviceName(value)}
            </option>
          ))}
        </SelectField>
        <p>
          Hands every pending and failed-replay dead letter of this service{narrowed ? ` (${narrowed} only)` : ''} back
          to its consumer, oldest first — at most {DEAD_LETTER_LIMITS.replayAllBatch} per run. Already applied events
          have no effect.
        </p>
        {result && (
          <Alert
            tone={result.outcome.failed > 0 ? 'warning' : 'success'}
            role="status"
            title={`${serviceName(result.service)}: replay finished`}
          >
            <p>
              Attempted {result.outcome.attempted} · replayed {result.outcome.replayed} · failed {result.outcome.failed}
            </p>
            {result.outcome.attempted >= DEAD_LETTER_LIMITS.replayAllBatch && (
              <p className="mt-1">There may be more: run it again.</p>
            )}
            {result.outcome.attempted === 0 && <p className="mt-1">Nothing was open.</p>}
          </Alert>
        )}
        {error && (
          <Alert tone="danger" role="alert">
            {error}
          </Alert>
        )}
      </div>
    </Dialog>
  )
}
