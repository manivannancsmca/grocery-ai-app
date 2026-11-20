import {
  type ComponentProps,
  type KeyboardEvent,
  type Ref,
  type RefObject,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
} from 'react'
import { Icon } from '../../icons'
import { cn } from '../../utils/cn'
import { formatLongDay, indiaDay } from '../../utils/indiaDate'
import { addMonths, formatMonth, inRange, type MonthView, monthGrid, monthOf, moveDay, moveMonth } from './calendarMath'

export type PickerKind = 'date' | 'time' | 'datetime-local'

const WEEKDAYS = [
  ['Su', 'Sunday'],
  ['Mo', 'Monday'],
  ['Tu', 'Tuesday'],
  ['We', 'Wednesday'],
  ['Th', 'Thursday'],
  ['Fr', 'Friday'],
  ['Sa', 'Saturday'],
] as const

const HOURS = Array.from({ length: 24 }, (_, hour) => String(hour).padStart(2, '0'))
const MINUTES = Array.from({ length: 12 }, (_, step) => String(step * 5).padStart(2, '0'))

const PICKER_LABEL: Record<PickerKind, string> = { date: 'Choose date', time: 'Choose time', 'datetime-local': 'Choose date and time' }

/** Writes a value the way typing would, so React `onChange` (controlled fields and react-hook-form) sees it. */
function setNativeValue(input: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value)
  input.dispatchEvent(new Event('input', { bubbles: true }))
}

function assignRef<T>(ref: Ref<T> | undefined, value: T | null) {
  if (typeof ref === 'function') ref(value)
  else if (ref) (ref as RefObject<T | null>).current = value
}

/** Splits an input value into its day and `HH:mm` parts. */
function splitValue(kind: PickerKind, value: string): { day: string; time: string } {
  if (kind === 'date') return { day: value.slice(0, 10), time: '' }
  if (kind === 'time') return { day: '', time: value.slice(0, 5) }
  return { day: value.slice(0, 10), time: value.slice(11, 16) }
}

interface DateTimeInputProps extends Omit<ComponentProps<'input'>, 'type'> {
  kind: PickerKind
  /** Classes of the text box (border, focus ring, state). */
  inputClassName: string
}

/**
 * A native date / time / datetime input (typing, validation, mobile pickers and form libraries keep working) with
 * the app's own popover calendar and time lists on devices with a fine pointer.
 */
export function DateTimeInput({ kind, inputClassName, className, ref, disabled, readOnly, onKeyDown, ...props }: DateTimeInputProps) {
  const inputRef = useRef<HTMLInputElement | null>(null)
  const wrapperRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  /** The field's value when the popover opened; `null` while it is closed. */
  const [openedWith, setOpenedWith] = useState<string | null>(null)
  const open = openedWith !== null

  function show() {
    setOpenedWith(inputRef.current?.value ?? '')
  }

  function close(refocus: boolean) {
    setOpenedWith(null)
    if (refocus) triggerRef.current?.focus()
  }

  // Close on a pointer press outside (the popover lives inside the wrapper).
  useEffect(() => {
    if (!open) return
    function onPointerDown(event: PointerEvent) {
      if (!wrapperRef.current?.contains(event.target as Node)) setOpenedWith(null)
    }
    document.addEventListener('pointerdown', onPointerDown)
    return () => document.removeEventListener('pointerdown', onPointerDown)
  }, [open])

  return (
    <div ref={wrapperRef} className={cn('relative', className)}>
      <input
        ref={(node) => {
          inputRef.current = node
          assignRef(ref, node)
        }}
        type={kind}
        disabled={disabled}
        readOnly={readOnly}
        onKeyDown={(event) => {
          onKeyDown?.(event)
          if (event.altKey && event.key === 'ArrowDown' && !disabled && !readOnly) {
            event.preventDefault()
            show()
          }
        }}
        className={cn(
          inputClassName,
          'h-10 pr-10 pl-3 tabular-nums',
          // On mouse / trackpad devices our popover replaces the browser's own picker button.
          'pointer-fine:[&::-webkit-calendar-picker-indicator]:hidden',
        )}
        {...props}
      />
      <button
        ref={triggerRef}
        type="button"
        aria-label={PICKER_LABEL[kind]}
        aria-haspopup="dialog"
        aria-expanded={open}
        disabled={disabled || readOnly}
        onClick={() => (open ? close(false) : show())}
        className={cn(
          'absolute inset-y-1 right-1 flex w-8 items-center justify-center rounded-lg text-slate-500 transition-colors pointer-coarse:hidden',
          'hover:bg-emerald-50 hover:text-emerald-700 focus-visible:outline-2 focus-visible:outline-emerald-600 disabled:pointer-events-none disabled:opacity-40',
          open && 'bg-emerald-50 text-emerald-700',
        )}
      >
        <Icon name={kind === 'time' ? 'clock' : 'calendar'} className="size-[1.1rem]" />
      </button>
      {openedWith !== null && (
        <PickerPopover
          kind={kind}
          initialValue={openedWith}
          inputRef={inputRef}
          anchor={wrapperRef}
          min={typeof props.min === 'string' ? props.min : undefined}
          max={typeof props.max === 'string' ? props.max : undefined}
          clearable={!props.required}
          onClose={close}
        />
      )}
    </div>
  )
}

interface PickerPopoverProps {
  kind: PickerKind
  initialValue: string
  inputRef: RefObject<HTMLInputElement | null>
  anchor: RefObject<HTMLDivElement | null>
  min?: string
  max?: string
  clearable: boolean
  onClose: (refocus: boolean) => void
}

function PickerPopover({ kind, initialValue, inputRef, anchor, min, max, clearable, onClose }: PickerPopoverProps) {
  const popoverRef = useRef<HTMLDivElement>(null)
  const labelId = useId()
  const today = indiaDay()
  const initial = splitValue(kind, initialValue)
  const [day, setDay] = useState(initial.day)
  const [time, setTime] = useState(initial.time)
  const [view, setView] = useState<MonthView>(() => monthOf(initial.day || today, monthOf(today, { year: 2026, month: 0 })))
  const [focusDay, setFocusDay] = useState(initial.day || (inRange(today, min, max) ? today : (min?.slice(0, 10) ?? today)))
  const showCalendar = kind !== 'time'
  const showTime = kind !== 'date'

  // Fixed position under the field (above when there is no room), kept in place while the page scrolls.
  useLayoutEffect(() => {
    function place() {
      const box = anchor.current?.getBoundingClientRect()
      const panel = popoverRef.current
      if (!box || !panel) return
      const { width, height } = panel.getBoundingClientRect()
      const left = Math.max(8, Math.min(box.left, window.innerWidth - width - 8))
      const below = box.bottom + 6
      const top = below + height > window.innerHeight - 8 && box.top - height - 6 > 8 ? box.top - height - 6 : below
      panel.style.left = `${left}px`
      panel.style.top = `${top}px`
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [anchor, view])

  // Focus the selected (or today's) day when the popover opens, and follow keyboard moves.
  useEffect(() => {
    const target = showCalendar
      ? popoverRef.current?.querySelector<HTMLElement>(`[data-day="${focusDay}"]`)
      : popoverRef.current?.querySelector<HTMLElement>('[aria-pressed="true"], button')
    target?.focus()
  }, [focusDay, showCalendar])

  // Selected hour and minute in view.
  useEffect(() => {
    for (const element of popoverRef.current?.querySelectorAll<HTMLElement>('[data-time-list] [aria-pressed="true"]') ?? []) {
      element.scrollIntoView?.({ block: 'center' })
    }
  }, [])

  function commit(nextDay: string, nextTime: string) {
    const input = inputRef.current
    if (!input) return
    if (kind === 'date') setNativeValue(input, nextDay)
    else if (kind === 'time') setNativeValue(input, nextTime)
    else setNativeValue(input, nextDay ? `${nextDay}T${nextTime || '09:00'}` : '')
  }

  function chooseDay(next: string) {
    setDay(next)
    setFocusDay(next)
    commit(next, time)
    if (kind === 'date') onClose(true)
  }

  function chooseTime(hour: string | null, minute: string | null) {
    const [currentHour = '09', currentMinute = '00'] = (time || '').split(':')
    const next = `${hour ?? (time ? currentHour : '09')}:${minute ?? (time ? currentMinute : '00')}`
    setTime(next)
    commit(day || today, next)
    if (kind === 'datetime-local' && !day) setDay(today)
    if (kind === 'time' && minute !== null) onClose(true)
  }

  function moveFocus(next: string) {
    if (!inRange(next, min, max)) return
    setFocusDay(next)
    setView(monthOf(next, view))
  }

  function onGridKeyDown(event: KeyboardEvent<HTMLTableElement>) {
    const moves: Record<string, () => string> = {
      ArrowLeft: () => moveDay(focusDay, -1),
      ArrowRight: () => moveDay(focusDay, 1),
      ArrowUp: () => moveDay(focusDay, -7),
      ArrowDown: () => moveDay(focusDay, 7),
      PageUp: () => moveMonth(focusDay, -1),
      PageDown: () => moveMonth(focusDay, 1),
      Home: () => moveDay(focusDay, -new Date(`${focusDay}T00:00:00Z`).getUTCDay()),
      End: () => moveDay(focusDay, 6 - new Date(`${focusDay}T00:00:00Z`).getUTCDay()),
    }
    const move = moves[event.key]
    if (!move) return
    event.preventDefault()
    moveFocus(move())
  }

  const [hour, minute] = time ? time.split(':') : [null, null]
  const grid = monthGrid(view)
  const visible = grid.flat().filter((cell) => cell.inMonth && inRange(cell.day, min, max))
  const tabbableDay = visible.some((cell) => cell.day === focusDay) ? focusDay : (visible.find((cell) => cell.day === day) ?? visible[0])?.day

  return (
    <div
      ref={popoverRef}
      role="dialog"
      aria-label={PICKER_LABEL[kind]}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.stopPropagation()
          onClose(true)
        }
      }}
      className="animate-pop-in fixed z-[70] flex gap-3 rounded-2xl border border-slate-200/80 bg-white p-3 shadow-2xl ring-1 shadow-slate-900/15 ring-black/5"
    >
      {showCalendar && (
        <div className="w-[16.5rem]">
          <div className="mb-2 flex items-center justify-between">
            <button
              type="button"
              aria-label="Previous month"
              onClick={() => setView((current) => addMonths(current, -1))}
              className="flex size-8 items-center justify-center rounded-lg text-slate-500 transition hover:bg-slate-100 hover:text-slate-800"
            >
              <Icon name="chevronRight" className="size-4 rotate-180" />
            </button>
            <p id={labelId} aria-live="polite" className="text-sm font-semibold text-slate-900">
              {formatMonth(view)}
            </p>
            <button
              type="button"
              aria-label="Next month"
              onClick={() => setView((current) => addMonths(current, 1))}
              className="flex size-8 items-center justify-center rounded-lg text-slate-500 transition hover:bg-slate-100 hover:text-slate-800"
            >
              <Icon name="chevronRight" className="size-4" />
            </button>
          </div>
          <table role="grid" aria-labelledby={labelId} onKeyDown={onGridKeyDown} className="w-full border-separate border-spacing-0.5 text-center">
            <thead>
              <tr>
                {WEEKDAYS.map(([short, long]) => (
                  <th key={short} scope="col" abbr={long} className="pb-1 text-[11px] font-semibold text-slate-400">
                    {short}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {grid.map((week) => (
                <tr key={week[0]?.day}>
                  {week.map(({ day: cellDay, inMonth }) => {
                    const selected = cellDay === day
                    const allowed = inRange(cellDay, min, max)
                    const isToday = cellDay === today
                    return (
                      <td key={cellDay} aria-selected={selected}>
                        <button
                          type="button"
                          data-day={cellDay}
                          tabIndex={cellDay === tabbableDay ? 0 : -1}
                          disabled={!allowed}
                          aria-label={formatLongDay(cellDay)}
                          aria-current={isToday ? 'date' : undefined}
                          onClick={() => chooseDay(cellDay)}
                          className={cn(
                            'relative flex size-9 items-center justify-center rounded-xl text-sm tabular-nums transition-all duration-150',
                            'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-emerald-600',
                            selected
                              ? 'bg-gradient-to-br from-emerald-500 to-teal-600 font-semibold text-white shadow-md shadow-emerald-600/30'
                              : allowed
                                ? cn('hover:scale-105 hover:bg-emerald-50 hover:text-emerald-800', inMonth ? 'text-slate-700' : 'text-slate-300')
                                : 'cursor-not-allowed text-slate-200 line-through',
                            isToday && !selected && 'font-bold text-emerald-700 ring-1 ring-emerald-300 ring-inset',
                          )}
                        >
                          {Number(cellDay.slice(8))}
                        </button>
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
          <div className="mt-2 flex items-center justify-between border-t border-slate-100 pt-2">
            <button
              type="button"
              disabled={!inRange(today, min, max)}
              onClick={() => chooseDay(today)}
              className="rounded-lg px-2.5 py-1 text-xs font-semibold text-emerald-700 transition hover:bg-emerald-50 disabled:opacity-40"
            >
              Today
            </button>
            {clearable && (
              <button
                type="button"
                onClick={() => {
                  setDay('')
                  if (inputRef.current) setNativeValue(inputRef.current, '')
                  onClose(true)
                }}
                className="rounded-lg px-2.5 py-1 text-xs font-semibold text-slate-500 transition hover:bg-slate-100 hover:text-slate-800"
              >
                Clear
              </button>
            )}
          </div>
        </div>
      )}
      {showTime && (
        <div className={cn('flex flex-col', showCalendar && 'border-l border-slate-100 pl-3')}>
          <p className="mb-2 flex h-8 items-center gap-1.5 text-sm font-semibold text-slate-900">
            <Icon name="clock" className="size-4" />
            {time || '--:--'}
          </p>
          <div className="flex h-60 gap-1.5">
            <TimeList label="Hour" values={HOURS} selected={hour} onChoose={(value) => chooseTime(value, null)} />
            <TimeList label="Minute" values={MINUTES} selected={minute} onChoose={(value) => chooseTime(null, value)} />
          </div>
          {kind === 'datetime-local' && (
            <button
              type="button"
              onClick={() => onClose(true)}
              className="mt-2 rounded-lg bg-gradient-to-b from-emerald-500 to-emerald-600 px-3 py-1.5 text-xs font-semibold text-white shadow-sm transition hover:to-emerald-700"
            >
              Done
            </button>
          )}
        </div>
      )}
    </div>
  )
}

function TimeList({ label, values, selected, onChoose }: { label: string; values: readonly string[]; selected: string | null; onChoose: (value: string) => void }) {
  return (
    <div role="group" aria-label={label} data-time-list className="flex w-12 flex-col gap-0.5 overflow-y-auto pr-0.5">
      {values.map((value) => {
        const pressed = value === selected
        return (
          <button
            key={value}
            type="button"
            aria-pressed={pressed}
            onClick={() => onChoose(value)}
            className={cn(
              'shrink-0 rounded-lg py-1.5 text-sm tabular-nums transition-colors',
              pressed ? 'bg-gradient-to-br from-emerald-500 to-teal-600 font-semibold text-white' : 'text-slate-600 hover:bg-emerald-50 hover:text-emerald-800',
            )}
          >
            {value}
          </button>
        )
      })}
    </div>
  )
}
