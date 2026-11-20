import { type KeyboardEvent, useId, useState } from 'react'
import { Icon } from '../../icons'
import { cn } from '../../utils/cn'

interface TagInputProps {
  label: string
  value: readonly string[]
  onChange: (next: string[]) => void
  onBlur?: () => void
  /** Turns typed text into a tag (e.g. `north indian` → `NORTH_INDIAN`). */
  normalize?: (raw: string) => string
  /** Returns an error message for an invalid tag, or null. */
  validate?: (tag: string) => string | null
  maxTags?: number
  placeholder?: string
  hint?: string
  error?: string
  required?: boolean
  disabled?: boolean
  suggestions?: readonly string[]
  formatTag?: (tag: string) => string
}

/** Text input that turns Enter / comma separated entries into removable tags. */
export function TagInput({
  label,
  value,
  onChange,
  onBlur,
  normalize = (raw) => raw.trim(),
  validate,
  maxTags,
  placeholder,
  hint,
  error,
  required,
  disabled,
  suggestions = [],
  formatTag = (tag) => tag,
}: TagInputProps) {
  const inputId = useId()
  const listId = useId()
  const [draft, setDraft] = useState('')
  const [entryError, setEntryError] = useState<string | null>(null)
  const atLimit = maxTags !== undefined && value.length >= maxTags

  function addTag(raw: string): boolean {
    const tag = normalize(raw)
    if (!tag) return false
    if (value.includes(tag)) {
      setEntryError(`${formatTag(tag)} is already added.`)
      return false
    }
    if (atLimit) {
      setEntryError(`You can add up to ${maxTags} entries.`)
      return false
    }
    const problem = validate?.(tag) ?? null
    if (problem) {
      setEntryError(problem)
      return false
    }
    onChange([...value, tag])
    setDraft('')
    setEntryError(null)
    return true
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault()
      addTag(draft)
    } else if (event.key === 'Backspace' && draft === '' && value.length > 0) {
      onChange(value.slice(0, -1))
    }
  }

  const shownError = entryError ?? error
  const describedBy = [shownError ? `${inputId}-error` : null, hint ? `${inputId}-hint` : null].filter(Boolean).join(' ')
  const remainingSuggestions = suggestions.filter((s) => !value.includes(s))

  return (
    <div className="space-y-1.5">
      <label htmlFor={inputId} className="block text-sm font-medium text-slate-700">
        {label}
        {required && (
          <span className="ml-0.5 text-red-600" aria-hidden="true">
            *
          </span>
        )}
      </label>
      <div
        className={cn(
          'flex min-h-10 flex-wrap items-center gap-1.5 rounded-lg border bg-white px-2 py-1.5 focus-within:ring-2',
          shownError
            ? 'border-red-400 focus-within:ring-red-500/25'
            : 'border-slate-300 focus-within:border-emerald-500 focus-within:ring-emerald-500/25',
          disabled && 'bg-slate-50',
        )}
      >
        <ul className="contents" aria-label={`${label} added`}>
          {value.map((tag) => (
            <li
              key={tag}
              className="inline-flex items-center gap-1 rounded-md bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-800 ring-1 ring-emerald-200 ring-inset"
            >
              {formatTag(tag)}
              {!disabled && (
                <button
                  type="button"
                  className="-mr-0.5 rounded p-0.5 text-emerald-700 hover:bg-emerald-100"
                  aria-label={`Remove ${formatTag(tag)}`}
                  onClick={() => onChange(value.filter((t) => t !== tag))}
                >
                  <Icon name="close" className="size-3" />
                </button>
              )}
            </li>
          ))}
        </ul>
        <input
          id={inputId}
          value={draft}
          disabled={disabled || atLimit}
          placeholder={atLimit ? `Maximum of ${maxTags} reached` : placeholder}
          list={remainingSuggestions.length > 0 ? listId : undefined}
          aria-invalid={shownError ? true : undefined}
          aria-describedby={describedBy || undefined}
          onChange={(event) => {
            setDraft(event.target.value)
            setEntryError(null)
          }}
          onKeyDown={onKeyDown}
          onBlur={() => {
            if (draft.trim()) addTag(draft)
            onBlur?.()
          }}
          className="h-7 min-w-32 flex-1 border-0 bg-transparent px-1 text-sm focus:ring-0 focus:outline-none disabled:cursor-not-allowed"
        />
        {remainingSuggestions.length > 0 && (
          <datalist id={listId}>
            {remainingSuggestions.map((s) => (
              <option key={s} value={formatTag(s)} />
            ))}
          </datalist>
        )}
      </div>
      {hint && (
        <p id={`${inputId}-hint`} className="text-xs text-slate-500">
          {hint}
        </p>
      )}
      {shownError && (
        <p id={`${inputId}-error`} className="text-xs font-medium text-red-600">
          {shownError}
        </p>
      )}
    </div>
  )
}
