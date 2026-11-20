import { type FormEvent, type KeyboardEvent, useId, useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { Spinner } from '../../shared/components/ui'
import { useDebouncedValue } from '../../shared/hooks/useDebouncedValue'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { MIN_SUGGEST_LENGTH } from './api'
import { PATH_SEPARATOR } from './categoryTree'
import { useSuggestions } from './hooks'
import { CategoryIcon } from './categoryVisuals'
import { ProductImage } from './ProductImage'

export const SUGGEST_DEBOUNCE_MS = 250

type SuggestionOption =
  | { kind: 'product'; key: string; id: string; name: string; detail: string; imageUrl: string | null }
  | { kind: 'category'; key: string; id: string; name: string; detail: string }

interface SearchBoxProps {
  /** Initial text (the current `q` on the search page). Remount with a `key` to reset it. */
  defaultValue?: string
  /** Free-text search; defaults to navigating to `/search?q=…`. */
  onSearch?: (q: string) => void
  size?: 'sm' | 'md' | 'lg'
  label?: string
  placeholder?: string
  className?: string
}

const SIZE_CLASSES = {
  sm: { input: 'h-9 pl-9 pr-9 text-sm', icon: 'left-2.5 size-4', button: 'size-7' },
  md: { input: 'h-11 pl-10 pr-11 text-sm', icon: 'left-3 size-5', button: 'size-8' },
  lg: { input: 'h-14 pl-12 pr-14 text-base', icon: 'left-4 size-5', button: 'size-10' },
} as const

/**
 * Product search with autocomplete — a WAI-ARIA combobox:
 * ↓/↑ move through the suggestions, Enter opens the highlighted one (a product page or a category's
 * results) or searches for the typed text, Escape closes the list (or clears the text).
 */
export function SearchBox({
  defaultValue = '',
  onSearch,
  size = 'md',
  label = 'Search products',
  placeholder = 'Search for tomatoes, atta, paneer…',
  className,
}: SearchBoxProps) {
  const navigate = useNavigate()
  const baseId = useId()
  const inputId = `${baseId}-input`
  const listboxId = `${baseId}-listbox`
  const [text, setText] = useState(defaultValue)
  const [open, setOpen] = useState(false)
  const [activeKey, setActiveKey] = useState<string | null>(null)

  const term = text.trim()
  const debounced = useDebouncedValue(term, SUGGEST_DEBOUNCE_MS)
  const suggestions = useSuggestions(debounced)
  const ready = term.length >= MIN_SUGGEST_LENGTH && debounced.length >= MIN_SUGGEST_LENGTH
  const data = ready ? suggestions.data : undefined

  const options = useMemo<SuggestionOption[]>(() => {
    if (!data) return []
    return [
      ...data.products.map((product) => ({
        kind: 'product' as const,
        key: `product-${product.id}`,
        id: product.id,
        name: product.name,
        detail: [product.brandName, product.unitLabel].filter(Boolean).join(' · '),
        imageUrl: product.primaryImageUrl,
      })),
      ...data.categories.map((category) => ({
        kind: 'category' as const,
        key: `category-${category.id}`,
        id: category.id,
        name: category.name,
        detail: category.path.length > 1 ? category.path.slice(0, -1).join(PATH_SEPARATOR) : 'Category',
      })),
    ]
  }, [data])

  const expanded = open && options.length > 0
  const activeIndex = activeKey === null ? -1 : options.findIndex((option) => option.key === activeKey)
  const activeOption = activeIndex >= 0 ? options[activeIndex] : undefined
  const optionId = (key: string) => `${baseId}-${key}`
  const classes = SIZE_CLASSES[size]

  function close() {
    setOpen(false)
    setActiveKey(null)
  }

  function choose(option: SuggestionOption) {
    close()
    if (option.kind === 'product') {
      void navigate(`/products/${encodeURIComponent(option.id)}`)
    } else {
      void navigate(`/search?categoryId=${encodeURIComponent(option.id)}`)
    }
  }

  function search(event?: FormEvent) {
    event?.preventDefault()
    close()
    if (onSearch) onSearch(term)
    else void navigate(term ? `/search?q=${encodeURIComponent(term)}` : '/search')
  }

  function move(step: 1 | -1) {
    if (options.length === 0) return
    setOpen(true)
    const next = activeIndex < 0 ? (step === 1 ? 0 : options.length - 1) : (activeIndex + step + options.length) % options.length
    setActiveKey(options[next]?.key ?? null)
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault()
        move(1)
        break
      case 'ArrowUp':
        event.preventDefault()
        move(-1)
        break
      case 'Enter':
        if (expanded && activeOption) {
          event.preventDefault()
          choose(activeOption)
        }
        // Otherwise the form submits the free text.
        break
      case 'Escape':
        if (expanded) {
          event.preventDefault()
          close()
        } else if (text) {
          event.preventDefault()
          setText('')
        }
        break
      case 'Tab':
        close()
        break
    }
  }

  const products = options.filter((option) => option.kind === 'product')
  const categories = options.filter((option) => option.kind === 'category')

  function renderOption(option: SuggestionOption) {
    const active = option.key === activeOption?.key
    return (
      <div
        key={option.key}
        id={optionId(option.key)}
        role="option"
        aria-selected={active}
        onMouseEnter={() => setActiveKey(option.key)}
        onClick={() => choose(option)}
        className={cn(
          'mx-1.5 flex cursor-pointer items-center gap-3 rounded-xl px-2 py-1.5 text-sm transition-colors',
          active ? 'bg-emerald-50 text-emerald-900' : 'text-slate-700',
        )}
      >
        {option.kind === 'product' ? (
          <ProductImage
            src={option.imageUrl}
            alt=""
            className="size-10 shrink-0 rounded-lg ring-1 ring-slate-200/70"
            iconClassName="size-5"
          />
        ) : (
          <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-emerald-50 ring-1 ring-emerald-100" aria-hidden="true">
            <CategoryIcon name={option.name} className="size-5" />
          </span>
        )}
        <span className="min-w-0 flex-1">
          <span className="block truncate font-semibold">{option.name}</span>{' '}
          {option.detail && <span className="block truncate text-xs text-slate-500">{option.detail}</span>}
        </span>
        <Icon
          name={option.kind === 'product' ? 'chevronRight' : 'arrowRight'}
          className={cn('size-4 text-emerald-600 transition-opacity', active ? 'opacity-100' : 'opacity-0')}
        />
      </div>
    )
  }

  return (
    <form role="search" onSubmit={search} className={cn('relative', className)}>
      <label htmlFor={inputId} className="sr-only">
        {label}
      </label>
      <div className="relative">
        <Icon name="search" className={cn('pointer-events-none absolute top-1/2 -translate-y-1/2', classes.icon)} />
        <input
          id={inputId}
          type="text"
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={expanded}
          aria-controls={listboxId}
          aria-activedescendant={expanded && activeOption ? optionId(activeOption.key) : undefined}
          autoComplete="off"
          enterKeyHint="search"
          spellCheck={false}
          value={text}
          placeholder={placeholder}
          onChange={(event) => {
            setText(event.target.value)
            setOpen(true)
            setActiveKey(null)
          }}
          onFocus={() => setOpen(true)}
          onBlur={close}
          onKeyDown={onKeyDown}
          className={cn(
            'block w-full rounded-xl border border-slate-300 bg-white text-slate-900 shadow-sm placeholder:text-slate-400',
            'focus:border-emerald-500 focus:ring-2 focus:ring-emerald-500/25 focus:outline-none',
            classes.input,
          )}
        />
        <div className="absolute inset-y-0 right-1.5 flex items-center gap-1">
          {ready && suggestions.isFetching && <Spinner size="sm" />}
          <button
            type="submit"
            aria-label="Search"
            className={cn(
              'flex items-center justify-center rounded-lg bg-emerald-600 text-white hover:bg-emerald-700',
              'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
              classes.button,
            )}
          >
            <Icon name="arrowRight" className="size-4" />
          </button>
        </div>
      </div>

      <div
        hidden={!expanded}
        // Keep focus in the input while clicking a suggestion.
        onMouseDown={(event) => event.preventDefault()}
        className="animate-fade-up absolute inset-x-0 top-full z-40 mt-2 overflow-hidden rounded-2xl border border-slate-200/80 bg-white text-left shadow-xl shadow-slate-900/10 ring-1 ring-slate-900/[0.03]"
      >
        <div id={listboxId} role="listbox" aria-label="Search suggestions" className="max-h-[26rem] overflow-y-auto py-1.5">
          {products.length > 0 && (
            <div role="group" aria-labelledby={`${baseId}-products`}>
              <div id={`${baseId}-products`} role="presentation" className="px-3.5 pt-2 pb-1.5 text-[0.7rem] font-semibold tracking-wider text-slate-400 uppercase">
                Products
              </div>
              {products.map(renderOption)}
            </div>
          )}
          {categories.length > 0 && (
            <div role="group" aria-labelledby={`${baseId}-categories`}>
              <div id={`${baseId}-categories`} role="presentation" className="px-3.5 pt-2 pb-1.5 text-[0.7rem] font-semibold tracking-wider text-slate-400 uppercase">
                Categories
              </div>
              {categories.map(renderOption)}
            </div>
          )}
        </div>
        <p className="flex items-center gap-2 border-t border-slate-100 bg-slate-50/80 px-3.5 py-2.5 text-xs text-slate-500">
          <kbd aria-hidden="true" className="inline-flex h-5 min-w-5 items-center justify-center rounded-md bg-white px-1 font-sans text-[0.7rem] font-semibold text-slate-600 shadow-xs ring-1 ring-slate-200">
            ↵
          </kbd>
          Press Enter to see all results for “{term}”
        </p>
      </div>
      <p aria-live="polite" className="sr-only">
        {expanded ? `${options.length} suggestion${options.length === 1 ? '' : 's'} available. Use the arrow keys to choose.` : ''}
      </p>
    </form>
  )
}
