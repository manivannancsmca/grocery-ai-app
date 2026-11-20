import { useCallback } from 'react'
import { useSearchParams } from 'react-router'

/** A change to the query string: a value sets the key, a list repeats it, `null` / `''` / `[]` removes it. */
export type ParamChanges = Record<string, string | readonly string[] | null | undefined>

interface UpdateOptions {
  /** Replace the history entry (typing) instead of pushing one (filter clicks, paging). */
  replace?: boolean
}

/**
 * The URL query string as page state: returns the current params and an updater that merges changes,
 * so filters survive reloads, can be shared, and work with the back button.
 */
export function useSearchParamsUpdater() {
  const [searchParams, setSearchParams] = useSearchParams()

  const updateParams = useCallback(
    (changes: ParamChanges, options: UpdateOptions = {}) => {
      setSearchParams(
        (current) => {
          const next = new URLSearchParams(current)
          for (const [key, value] of Object.entries(changes)) {
            next.delete(key)
            if (value === null || value === undefined || value === '') continue
            if (typeof value === 'string') next.set(key, value)
            else value.forEach((item) => next.append(key, item))
          }
          return next
        },
        { replace: options.replace ?? false },
      )
    },
    [setSearchParams],
  )

  return [searchParams, updateParams] as const
}

/** `?page=3` (1-based in the URL, readable for people) → API page index 2. */
export function pageFromParam(value: string | null): number {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed - 1 : 0
}

/** API page index → `?page=` value (`null` for the first page keeps URLs short). */
export function pageToParam(page: number): string | null {
  return page > 0 ? String(page + 1) : null
}
