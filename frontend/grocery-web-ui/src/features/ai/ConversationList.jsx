/** The assistant's conversation history: open, rename and delete past conversations. */

import { useId, useState } from 'react'
import { errorMessage } from '../../shared/api/problemDetail'
import { Alert, Button, ConfirmDialog, EmptyState, Input, Spinner } from '../../shared/components/ui'
import { useNow } from '../../shared/hooks/useNow'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDateTime, formatRelativeTime } from '../../shared/utils/format'
import { useConversations, useDeleteConversation, useRenameConversation } from './hooks'

/** @typedef {import('./api').ConversationSummary} ConversationSummary */

/** Longest conversation title (the server's limit). */
const TITLE_LIMIT = 80

/**
 * Renames one conversation in place: Enter or "Save" keeps the new title, Escape or "Cancel" keeps the old one.
 * @param {{ conversation: ConversationSummary, onDone: () => void }} props
 */
function RenameForm({ conversation, onDone }) {
  const rename = useRenameConversation()
  const [title, setTitle] = useState(conversation.title)
  const [error, setError] = useState(null)
  const inputId = useId()
  const errorId = useId()
  const clean = title.replace(/\s+/g, ' ').trim()

  async function save(event) {
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

/**
 * @typedef {object} ConversationListProps
 * @property {string} agent
 * @property {string | null} currentId
 * @property {(id: string) => void} onOpen
 * @property {() => void} onNew
 * @property {(id: string) => void} onDeleted A deleted conversation that was open is left.
 */

/** @param {ConversationListProps} props */
export function ConversationList({ agent, currentId, onOpen, onNew, onDeleted }) {
  const list = useConversations(agent, true)
  const remove = useDeleteConversation()
  const now = useNow(60_000)
  const [toDelete, setToDelete] = useState(null)
  const [deleteError, setDeleteError] = useState(null)
  const [renamingId, setRenamingId] = useState(null)

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
        <Alert
          tone="danger"
          role="alert"
          actions={
            <Button size="sm" variant="secondary" onClick={() => void list.refetch()}>
              Try again
            </Button>
          }
        >
          Could not load your conversations: {list.error.message}
        </Alert>
      ) : list.data.content.length === 0 ? (
        <EmptyState
          title="No conversations yet"
          description="Ask the assistant something; your conversations are kept here."
        />
      ) : (
        <ul aria-label="Conversations" className="divide-y divide-slate-100 rounded-xl border border-slate-200">
          {list.data.content.map((conversation) => (
            <li
              key={conversation.id}
              className={cn('flex items-center gap-2 px-3 py-2', conversation.id === currentId && 'bg-emerald-50/60')}
            >
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
                    <span className="block truncate text-sm font-medium text-slate-900">
                      {conversation.title || 'Untitled conversation'}
                    </span>
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
