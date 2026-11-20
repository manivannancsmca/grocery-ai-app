import { type FormEvent, useId, useState } from 'react'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Badge, Button, Card, ErrorState, Input, Skeleton } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDateTime } from '../../shared/utils/format'
import { SETTINGS_ERRORS, type SettingsView } from './api'
import {
  type AreaDefinition,
  type AreaDraft,
  buildUpdate,
  draftFrom,
  type FieldDraft,
  fieldGroups,
  fieldState,
  type FieldState,
  formatSetting,
  inputText,
  serverFieldErrors,
  type SettingField,
} from './fields'
import { useAreaSettings, useSaveAreaSettings } from './hooks'
import { SettingsHistoryDrawer } from './SettingsHistoryDrawer'

interface SettingsAreaCardProps {
  definition: AreaDefinition
  /** SUPER_ADMIN / OPS_ADMIN; everyone else sees the card read-only. */
  canManage: boolean
}

/**
 * One area of the platform settings (one owning service): its own query, so a service that is down only affects its
 * card. Loading and error states keep the card's header, so the page never jumps.
 */
export function SettingsAreaCard({ definition, canManage }: SettingsAreaCardProps) {
  const settings = useAreaSettings(definition.area)
  if (settings.data) {
    return <AreaForm definition={definition} view={settings.data} canManage={canManage} onReload={() => void settings.refetch()} />
  }
  return (
    <Card icon={definition.icon} title={definition.title} description={definition.description}>
      {settings.isError ? (
        <ErrorState
          title="These settings could not be loaded"
          message={`${errorMessage(settings.error)} The other settings on this page still work.`}
          onRetry={() => void settings.refetch()}
        />
      ) : (
        <div aria-label={`Loading ${definition.title} settings`} className="space-y-3">
          {definition.fields.slice(0, 3).map((field) => (
            <Skeleton key={field.key} className="h-16 w-full rounded-xl" />
          ))}
        </div>
      )}
    </Card>
  )
}

interface AreaFormProps {
  definition: AreaDefinition
  view: SettingsView
  canManage: boolean
  onReload: () => void
}

/**
 * The card's form. The inputs follow the server view until the admin edits one; from then on the draft (with the
 * version it started from, sent as `expectedVersion`) holds the edits until it is saved, undone or reloaded.
 */
function AreaForm({ definition, view, canManage, onReload }: AreaFormProps) {
  const toast = useToast()
  const save = useSaveAreaSettings(definition.area)
  const [draft, setDraft] = useState<AreaDraft | null>(null)
  const [failure, setFailure] = useState<unknown>(null)
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({})
  const [historyOpen, setHistoryOpen] = useState(false)

  const current = draft ?? draftFrom(definition, view)
  const states = new Map(definition.fields.map((field) => [field.key, fieldState(field, current.fields[field.key], view)]))
  const invalid = [...states.values()].some((state) => state.error !== null)
  const unsaved = [...states.values()].some((state) => state.unsaved)
  const conflict = hasErrorCode(failure, SETTINGS_ERRORS.changed)
  const editable = canManage && !save.isPending
  const customCount = view.overridden.filter((key) => states.has(key)).length

  function edit(key: string, change: Partial<FieldDraft>) {
    setDraft((previous) => {
      const base = previous ?? draftFrom(definition, view)
      const field = base.fields[key] ?? { text: '', useDefault: true }
      return { ...base, fields: { ...base.fields, [key]: { ...field, ...change } } }
    })
    setServerErrors((previous) => {
      if (!(key in previous)) return previous
      const next = { ...previous }
      delete next[key]
      return next
    })
  }

  function clearFeedback() {
    setFailure(null)
    setServerErrors({})
  }

  function undo() {
    setDraft(null)
    clearFeedback()
  }

  function reload() {
    setDraft(null)
    clearFeedback()
    onReload()
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canManage || !unsaved || invalid || conflict || save.isPending) return
    const update = buildUpdate(definition, current, view)
    const submitted = current
    // The card now shows the optimistic view from the cache; the edits come back if the save fails.
    setDraft(null)
    clearFeedback()
    save.mutate(update, {
      onSuccess: () => toast.success(`${definition.title}: settings saved`),
      onError: (error) => {
        setDraft(submitted)
        setFailure(error)
        setServerErrors(serverFieldErrors(error, definition).byKey)
      },
    })
  }

  const otherServerErrors = failure && !conflict ? serverFieldErrors(failure, definition).other : []

  return (
    <form aria-label={definition.title} noValidate onSubmit={submit} className="min-w-0">
      <Card
        icon={definition.icon}
        title={definition.title}
        description={definition.description}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone={customCount > 0 ? 'amber' : 'green'} dot>
              {customCount > 0 ? `${customCount} custom value${customCount === 1 ? '' : 's'}` : 'All defaults'}
            </Badge>
            <Button variant="secondary" size="sm" onClick={() => setHistoryOpen(true)} aria-label={`${definition.title} history`}>
              <Icon name="clock" className="size-4" />
              History
            </Button>
          </div>
        }
      >
        <div className="space-y-4">
          <p className="flex items-start gap-2 rounded-xl bg-sky-50/70 px-3 py-2 text-xs leading-relaxed text-sky-900 ring-1 ring-sky-100">
            <Icon name="clock" className="mt-px size-4 shrink-0" />
            <span>{definition.appliesTo}</span>
          </p>

          {conflict && (
            <Alert
              tone="warning"
              role="alert"
              title="Someone else changed these settings — reload"
              actions={
                <Button size="sm" variant="secondary" onClick={reload}>
                  <Icon name="refresh" className="size-4" />
                  Reload
                </Button>
              }
            >
              Your edits are kept here until you reload. Reloading shows the current values; then make your change again.
            </Alert>
          )}
          {failure !== null && !conflict && (
            <Alert tone="danger" role="alert" title="The settings were not saved">
              {Object.keys(serverErrors).length > 0 ? 'Some values were not accepted: see the fields below.' : errorMessage(failure)}
              {otherServerErrors.length > 0 && (
                <ul className="mt-1 list-disc pl-5">
                  {otherServerErrors.map((message) => (
                    <li key={message}>{message}</li>
                  ))}
                </ul>
              )}
            </Alert>
          )}

          {fieldGroups(definition).map((group) => (
            <fieldset key={group.title ?? 'fields'} className="min-w-0 space-y-3">
              {group.title && <legend className="mb-2 text-xs font-semibold tracking-wider text-slate-500 uppercase">{group.title}</legend>}
              <div className="grid gap-3 sm:grid-cols-2">
                {group.fields.map((field) => (
                  <SettingInput
                    key={field.key}
                    field={field}
                    draft={current.fields[field.key]}
                    state={states.get(field.key) ?? { value: null, error: null, changed: false, unsaved: false }}
                    defaultValue={formatSetting(field, view.defaults[field.key])}
                    serverError={serverErrors[field.key]}
                    editable={editable}
                    canManage={canManage}
                    onChange={(text) => edit(field.key, { text, useDefault: false })}
                    onReset={() => edit(field.key, { text: inputText(view.defaults[field.key]), useDefault: true })}
                  />
                ))}
              </div>
            </fieldset>
          ))}

          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-4">
            <p className="min-w-0 text-xs text-slate-500">
              {view.updatedAt ? (
                <>
                  Last changed by <span className="font-medium text-slate-700">{view.updatedBy ?? 'an admin'}</span> ·{' '}
                  {formatDateTime(view.updatedAt)}
                </>
              ) : (
                'Never changed — using the defaults'
              )}
            </p>
            {canManage && (
              <div className="flex flex-wrap items-center gap-2">
                {unsaved && !save.isPending && (
                  <Button variant="ghost" size="sm" onClick={undo}>
                    Undo changes
                  </Button>
                )}
                <Button type="submit" size="sm" disabled={!unsaved || invalid || conflict || save.isPending}>
                  {save.isPending ? 'Saving…' : 'Save changes'}
                </Button>
              </div>
            )}
          </div>
        </div>
      </Card>
      {/* A drawer rendered into <body>: it holds no fields and never submits this form. */}
      {historyOpen && <SettingsHistoryDrawer definition={definition} onClose={() => setHistoryOpen(false)} />}
    </form>
  )
}

interface SettingInputProps {
  field: SettingField
  draft: FieldDraft | undefined
  state: FieldState
  /** The default with its unit: `48 h`, `₹499`. */
  defaultValue: string
  serverError: string | undefined
  editable: boolean
  canManage: boolean
  onChange: (text: string) => void
  onReset: () => void
}

/** One setting: label with its markers, the input with its unit, the default, the hint and any error. */
function SettingInput({ field, draft, state, defaultValue, serverError, editable, canManage, onChange, onReset }: SettingInputProps) {
  const id = useId()
  const error = state.error ?? serverError ?? null
  const describedBy = [`${id}-default`, error ? `${id}-error` : null].filter(Boolean).join(' ')
  const name = field.group ? `${field.group} ${field.label.toLowerCase()}` : field.label
  return (
    <div
      className={cn(
        'min-w-0 rounded-xl border p-3 transition-colors',
        state.changed ? 'border-amber-200 bg-amber-50/40' : 'border-slate-200/70 bg-slate-50/50',
        error && 'border-red-200 bg-red-50/40',
      )}
    >
      <div className="flex flex-wrap items-center justify-between gap-x-2 gap-y-1">
        <label htmlFor={id} className="text-sm font-medium text-slate-700">
          {field.group && <span className="sr-only">{field.group} </span>}
          {field.label}
        </label>
        <span className="flex items-center gap-1.5">
          {state.changed && (
            <Badge tone="amber" dot>
              Changed
            </Badge>
          )}
          {state.unsaved && <Badge tone="blue">Unsaved</Badge>}
        </span>
      </div>
      <div className="mt-2 flex items-center gap-2">
        {field.kind === 'money' && (
          <span aria-hidden="true" className="text-sm font-medium text-slate-500">
            ₹
          </span>
        )}
        <Input
          id={id}
          value={draft?.text ?? ''}
          onChange={(event) => onChange(event.target.value)}
          disabled={!editable}
          invalid={Boolean(error)}
          inputMode={field.kind === 'integer' ? 'numeric' : 'decimal'}
          autoComplete="off"
          aria-describedby={describedBy}
          className="min-w-0 flex-1 tabular-nums"
        />
        {field.unit && <span className="shrink-0 text-sm text-slate-500">{field.unit}</span>}
        {canManage && state.changed && (
          <Button variant="ghost" size="sm" onClick={onReset} disabled={!editable} aria-label={`Reset ${name} to the default`} title="Reset to the default">
            <Icon name="refresh" className="size-4" />
            <span className="hidden sm:inline">Reset</span>
          </Button>
        )}
      </div>
      <p id={`${id}-default`} className="mt-1.5 text-xs text-slate-500">
        Default: {defaultValue}
        {field.hint && (
          <>
            {' · '}
            <span className="text-slate-400">{field.hint}</span>
          </>
        )}
      </p>
      {error && (
        <p id={`${id}-error`} className="mt-1 text-xs font-medium text-red-600">
          {error}
        </p>
      )}
    </div>
  )
}
