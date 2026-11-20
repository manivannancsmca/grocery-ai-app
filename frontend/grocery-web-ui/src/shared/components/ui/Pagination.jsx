import { Icon } from '../../icons'
import { cn } from '../../utils/cn'
import { pageWindow } from './pageWindow'

/**
 * @typedef {object} PaginationProps
 * @property {number} page 0-based page index, as the API uses it.
 * @property {number} size
 * @property {number} totalElements
 * @property {number} totalPages
 * @property {(page: number) => void} onPageChange
 * @property {boolean} [disabled]
 */

const stepClasses =
  'inline-flex h-9 items-center gap-1 rounded-xl px-3 text-sm font-semibold text-slate-600 ring-1 ring-slate-200 ring-inset transition-all ' +
  'enabled:hover:-translate-y-px enabled:hover:bg-emerald-50 enabled:hover:text-emerald-800 enabled:hover:ring-emerald-200 disabled:cursor-not-allowed disabled:opacity-40 ' +
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

/** @param {PaginationProps} props */
export function Pagination({ page, size, totalElements, totalPages, onPageChange, disabled }) {
  if (totalElements === 0) return null
  const from = page * size + 1
  const to = Math.min(totalElements, (page + 1) * size)
  const pages = Math.max(totalPages, 1)
  const lastPage = pages - 1

  return (
    <nav aria-label="Pagination" className="flex flex-col items-center justify-between gap-3 pt-4 sm:flex-row">
      <p className="text-sm text-slate-500">
        Showing <span className="font-semibold text-slate-800 tabular-nums">{from}</span>–
        <span className="font-semibold text-slate-800 tabular-nums">{to}</span> of{' '}
        <span className="font-semibold text-slate-800 tabular-nums">{totalElements.toLocaleString('en-IN')}</span>
      </p>
      <div className="flex items-center gap-1.5">
        <button
          type="button"
          aria-label="Previous"
          className={stepClasses}
          onClick={() => onPageChange(page - 1)}
          disabled={disabled || page <= 0}
        >
          <Icon name="chevronRight" className="size-3.5 rotate-180" />
          <span className="hidden sm:inline">Previous</span>
        </button>
        <ol className="flex items-center gap-1">
          {pageWindow(page, pages).map((index, position) =>
            index === null ? (
              <li key={`gap-${position}`} aria-hidden="true" className="w-6 text-center text-sm text-slate-400">
                …
              </li>
            ) : (
              <li
                key={index}
                className={cn(
                  index !== page &&
                    Math.abs(index - page) > 1 &&
                    index !== 0 &&
                    index !== lastPage &&
                    'hidden sm:block',
                )}
              >
                <button
                  type="button"
                  aria-label={`Page ${index + 1}`}
                  aria-current={index === page ? 'page' : undefined}
                  disabled={disabled}
                  onClick={() => index !== page && onPageChange(index)}
                  className={cn(
                    'inline-flex size-9 items-center justify-center rounded-xl text-sm font-semibold tabular-nums transition-all',
                    'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 disabled:cursor-not-allowed',
                    index === page
                      ? 'bg-gradient-to-br from-emerald-500 to-teal-600 text-white shadow-md shadow-emerald-600/25'
                      : 'text-slate-600 hover:-translate-y-px hover:bg-emerald-50 hover:text-emerald-800',
                  )}
                >
                  {index + 1}
                </button>
              </li>
            ),
          )}
        </ol>
        <button
          type="button"
          aria-label="Next"
          className={stepClasses}
          onClick={() => onPageChange(page + 1)}
          disabled={disabled || page >= lastPage}
        >
          <span className="hidden sm:inline">Next</span>
          <Icon name="chevronRight" className="size-3.5" />
        </button>
      </div>
    </nav>
  )
}
