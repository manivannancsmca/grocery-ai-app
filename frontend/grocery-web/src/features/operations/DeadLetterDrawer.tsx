import { type ReactNode, useState } from 'react'
import { ApiError, errorMessage, hasErrorCode, problemString } from '../../shared/api/problemDetail'
import { useAuth } from '../../shared/auth/useAuth'
import { ReasonDialog } from '../../shared/components/ReasonDialog'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Badge, Button, DescriptionList, Dialog, ErrorState, Spinner } from '../../shared/components/ui'
import { formatDateTime } from '../../shared/utils/format'
import { DEAD_LETTER_ERRORS, DEAD_LETTER_LIMITS, type DeadLetterView, isOpenDeadLetter } from './api'
import { TraceDetails } from './ConnectorsPanel'
import { type DeadLetterRow, useDeadLetter, useDiscardDeadLetter, useReplayDeadLetter } from './hooks'
import { deadLetterStatusLabel, deadLetterStatusTone, prettyPayload, serviceName } from './labels'

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h3 className="mb-3 text-sm font-semibold text-slate-900">{title}</h3>
      {children}
    </section>
  )
}

export function DeadLetterStatusBadge({ status }: { status: string }) {
  return (
    <Badge tone={deadLetterStatusTone(status)} dot>
      {deadLetterStatusLabel(status)}
    </Badge>
  )
}

/** The 409 DEAD_LETTER_CLOSED message, naming the status it already has (`currentStatus`). */
function closedMessage(error: unknown): string {
  const current = problemString(error, 'currentStatus')
  const already = current ? `already ${deadLetterStatusLabel(current).toLowerCase()}` : 'already closed'
  return `This dead letter is ${already} (someone else got there first), so nothing was done. It has been refreshed.`
}

function StatusNote({ view }: { view: DeadLetterView }) {
  const by = [view.lastActionBy, view.lastActionAt ? formatDateTime(view.lastActionAt) : null].filter(Boolean).join(' · ')
  if (view.status === 'REPLAY_FAILED') {
    return (
      <Alert tone="danger" title="The last replay failed">
        {view.lastError && <p className="font-mono text-xs break-words">{view.lastError}</p>}
        <p className="mt-1 text-xs">{by ? `${by}. ` : ''}It is still in the dead-letter topic: replay it again or discard it.</p>
      </Alert>
    )
  }
  if (view.status === 'REPLAYED') {
    return (
      <Alert tone="success" title="Replayed">
        The consumer processed the event{by ? ` (${by})` : ''}. Nothing is left to do.
      </Alert>
    )
  }
  if (view.status === 'DISCARDED') {
    return (
      <Alert tone="neutral" title="Discarded">
        {by ? `${by}.` : null} {view.lastError && <span className="block break-words">{view.lastError}</span>}
      </Alert>
    )
  }
  return null
}

interface DeadLetterDrawerProps {
  /** The clicked row: shown at once, while the payload and stack trace load. */
  row: DeadLetterRow
  onClose: () => void
}

/**
 * One dead letter (`GET /api/admin/ops/<service>/dead-letters/{id}`): where it came from, the exception, the stack
 * trace (folded), the decoded payload and its bookkeeping — with Replay and Discard for SUPER_ADMIN / OPS_ADMIN.
 */
export function DeadLetterDrawer({ row, onClose }: DeadLetterDrawerProps) {
  const { service, id } = row
  const { can } = useAuth()
  const toast = useToast()
  const detail = useDeadLetter(service, id)
  const replay = useReplayDeadLetter()
  const discard = useDiscardDeadLetter()
  const [discardOpen, setDiscardOpen] = useState(false)
  const [conflict, setConflict] = useState<string | null>(null)

  const view: DeadLetterView = detail.data ?? row
  const open = isOpenDeadLetter(view.status)
  const canManage = can('deadLetters.manage')
  const busy = replay.isPending || discard.isPending

  async function replayIt() {
    setConflict(null)
    try {
      const result = await replay.mutateAsync({ service, id })
      if (result.status === 'REPLAYED') {
        toast.success('Dead letter replayed', { description: 'Its consumer processed the event again.' })
      } else {
        toast.error('Replay failed', { description: result.lastError ?? 'The consumer failed again. Retry or discard it.' })
      }
    } catch (error) {
      if (hasErrorCode(error, DEAD_LETTER_ERRORS.closed)) {
        setConflict(closedMessage(error))
        return
      }
      toast.error('Could not replay the dead letter', { description: errorMessage(error) })
    }
  }

  async function discardIt(reason: string) {
    setConflict(null)
    try {
      await discard.mutateAsync({ service, id, reason })
      setDiscardOpen(false)
      toast.success('Dead letter discarded')
    } catch (error) {
      if (hasErrorCode(error, DEAD_LETTER_ERRORS.closed)) {
        setDiscardOpen(false)
        setConflict(closedMessage(error))
        return
      }
      throw error
    }
  }

  const payload = prettyPayload(detail.data?.payload)

  return (
    <>
      <Dialog
        open
        variant="drawer"
        icon="warning"
        onClose={onClose}
        dismissible={!busy}
        title={view.eventType ?? 'Unreadable event'}
        description={
          <span className="flex flex-wrap items-center gap-1.5">
            <DeadLetterStatusBadge status={view.status} />
            <Badge tone="gray">{serviceName(service)}</Badge>
          </span>
        }
        footer={
          canManage && open ? (
            <>
              <Button variant="secondary" onClick={() => setDiscardOpen(true)} disabled={busy}>
                Discard
              </Button>
              <Button onClick={() => void replayIt()} disabled={busy}>
                {replay.isPending && <Spinner size="sm" className="[&_svg]:text-white" />}
                Replay
              </Button>
            </>
          ) : undefined
        }
      >
        <div className="space-y-6">
          {conflict && (
            <Alert tone="warning" role="alert">
              {conflict}
            </Alert>
          )}
          <StatusNote view={view} />
          {open && !canManage && (
            <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">
              Your admin role can inspect dead letters but not replay or discard them. Super and operations admins can.
            </p>
          )}

          <Section title="Details">
            <DescriptionList
              items={[
                { label: 'Service', value: serviceName(service) },
                { label: 'Consumer group', value: <span className="font-mono text-xs">{view.consumerGroup}</span> },
                {
                  label: 'Original record',
                  value: (
                    <span className="font-mono text-xs">
                      {view.originalTopic} · partition {view.originalPartition} · offset {view.originalOffset}
                    </span>
                  ),
                  wide: true,
                },
                { label: 'Dead-letter record', value: <span className="font-mono text-xs break-all">{view.id}</span>, wide: true },
                { label: 'Dead-lettered', value: formatDateTime(view.deadLetteredAt) },
                { label: 'Event occurred', value: view.occurredAt ? formatDateTime(view.occurredAt) : null },
                {
                  label: 'Event',
                  value: view.eventType ? `${view.eventType}${view.eventVersion !== null ? ` v${view.eventVersion}` : ''}` : null,
                },
                { label: 'Event ID', value: view.eventId && <span className="font-mono text-xs break-all">{view.eventId}</span> },
                { label: 'Key', value: view.key && <span className="font-mono text-xs break-all">{view.key}</span> },
                { label: 'Status', value: <DeadLetterStatusBadge status={view.status} /> },
                { label: 'Replay attempts', value: String(view.attempts) },
                { label: 'Last error', value: view.lastError && <span className="font-mono text-xs">{view.lastError}</span>, wide: true },
                {
                  label: 'Last action',
                  value: [view.lastActionBy, view.lastActionAt ? formatDateTime(view.lastActionAt) : null].filter(Boolean).join(' · ') || null,
                  wide: true,
                },
              ]}
            />
          </Section>

          <Section title="Exception">
            {view.exceptionClass || view.exceptionMessage ? (
              <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2">
                {view.exceptionClass && <p className="font-mono text-xs font-semibold break-all text-red-800">{view.exceptionClass}</p>}
                {view.exceptionMessage && <p className="mt-1 text-sm break-words text-red-800">{view.exceptionMessage}</p>}
              </div>
            ) : (
              <p className="text-sm text-slate-500">No exception was recorded.</p>
            )}
            {detail.data?.stackTrace && <TraceDetails trace={detail.data.stackTrace} label="Stack trace" />}
          </Section>

          <Section title="Payload">
            {detail.isPending ? (
              <Spinner size="sm" label="Loading the payload" />
            ) : detail.isError ? (
              <div className="space-y-3">
                <ErrorState
                  title="Could not load the full dead letter"
                  message={detail.error.message}
                  correlationId={detail.error instanceof ApiError ? detail.error.correlationId : null}
                  onRetry={() => void detail.refetch()}
                />
                {row.payloadPreview && (
                  <pre className="max-h-60 overflow-auto rounded-lg bg-slate-50 p-3 font-mono text-xs whitespace-pre-wrap text-slate-700">
                    {row.payloadPreview}
                  </pre>
                )}
              </div>
            ) : payload ? (
              <pre
                aria-label="Payload"
                className="max-h-96 overflow-auto rounded-lg bg-slate-50 p-3 font-mono text-xs whitespace-pre-wrap text-slate-800 ring-1 ring-slate-200 ring-inset"
              >
                {payload}
              </pre>
            ) : (
              <p className="text-sm text-slate-500">The payload could not be decoded{view.payloadPreview ? '; preview:' : '.'}</p>
            )}
            {!detail.isPending && !detail.isError && !payload && view.payloadPreview && (
              <pre className="mt-2 max-h-60 overflow-auto rounded-lg bg-slate-50 p-3 font-mono text-xs whitespace-pre-wrap text-slate-700">
                {view.payloadPreview}
              </pre>
            )}
          </Section>
        </div>
      </Dialog>

      <ReasonDialog
        open={discardOpen}
        title="Discard this dead letter?"
        description="It stays in the dead-letter topic but is marked as dealt with and can no longer be replayed. The reason is kept with it."
        confirmLabel="Discard"
        cancelLabel="Keep it"
        maxLength={DEAD_LETTER_LIMITS.discardReason}
        requiredMessage="Enter a reason: why is this event not needed?"
        onClose={() => setDiscardOpen(false)}
        onSubmit={discardIt}
      />
    </>
  )
}
