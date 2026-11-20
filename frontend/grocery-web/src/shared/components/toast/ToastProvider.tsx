import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Icon, type IconName } from '../../icons'
import { cn } from '../../utils/cn'
import { ToastContext, type ToastApi, type ToastOptions, type ToastTone } from './toastContext'

interface ToastItem {
  id: number
  tone: ToastTone
  message: string
  description?: string
  durationMs: number
}

const TONES: Record<ToastTone, { icon: IconName; accent: string; bar: string; title: string }> = {
  success: { icon: 'checkCircle', accent: 'from-emerald-400 to-teal-500', bar: 'bg-emerald-500', title: 'text-emerald-950' },
  error: { icon: 'alertCircle', accent: 'from-red-400 to-rose-500', bar: 'bg-red-500', title: 'text-red-950' },
  warning: { icon: 'warning', accent: 'from-amber-400 to-orange-500', bar: 'bg-amber-500', title: 'text-amber-950' },
  info: { icon: 'info', accent: 'from-sky-400 to-blue-500', bar: 'bg-sky-500', title: 'text-slate-900' },
}

let nextId = 1

/** One toast: auto-dismisses after its duration, paused while the pointer or keyboard focus is on it. */
function Toast({ toast, onDismiss }: { toast: ToastItem; onDismiss: (id: number) => void }) {
  const [paused, setPaused] = useState(false)
  const remaining = useRef(toast.durationMs)

  useEffect(() => {
    if (paused) return
    const startedAt = Date.now()
    const timer = setTimeout(() => onDismiss(toast.id), remaining.current)
    return () => {
      clearTimeout(timer)
      remaining.current = Math.max(0, remaining.current - (Date.now() - startedAt))
    }
  }, [paused, toast.id, onDismiss])

  const tone = TONES[toast.tone]
  return (
    <li
      role={toast.tone === 'error' ? 'alert' : 'status'}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
      className="animate-toast-in pointer-events-auto relative flex w-full items-start gap-3 overflow-hidden rounded-2xl border border-slate-200/70 bg-white/95 p-3.5 pr-2 pl-4 shadow-xl ring-1 shadow-slate-900/10 ring-black/5 backdrop-blur"
    >
      <span aria-hidden="true" className={cn('absolute inset-y-0 left-0 w-1 bg-gradient-to-b', tone.accent)} />
      <Icon name={tone.icon} className="mt-px size-5" />
      <div className="min-w-0 flex-1">
        <p className={cn('text-sm font-semibold', tone.title)}>{toast.message}</p>
        {toast.description && <p className="mt-0.5 text-xs break-words text-slate-500">{toast.description}</p>}
      </div>
      <button
        type="button"
        onClick={() => onDismiss(toast.id)}
        className="rounded-lg p-1 text-slate-400 transition-all hover:rotate-90 hover:bg-slate-100 hover:text-slate-600"
        aria-label="Dismiss notification"
      >
        <Icon name="close" className="size-4" />
      </button>
      {/* Time left before it closes (stops while paused). */}
      <span
        aria-hidden="true"
        className={cn('absolute bottom-0 left-0 h-0.5 w-full origin-left opacity-60', tone.bar)}
        style={{ animation: `shrink-x ${toast.durationMs}ms linear forwards`, animationPlayState: paused ? 'paused' : 'running' }}
      />
    </li>
  )
}

/** App-wide toast notifications (bottom centre on phones, bottom right on larger screens). */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([])

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id))
  }, [])

  const show = useCallback((tone: ToastTone, message: string, options?: ToastOptions) => {
    const toast: ToastItem = {
      id: nextId++,
      tone,
      message,
      description: options?.description,
      durationMs: options?.durationMs ?? (tone === 'error' ? 7_000 : 4_000),
    }
    setToasts((current) => [...current.slice(-3), toast])
  }, [])

  const api = useMemo<ToastApi>(
    () => ({
      show,
      success: (message, options) => show('success', message, options),
      error: (message, options) => show('error', message, options),
      warning: (message, options) => show('warning', message, options),
      info: (message, options) => show('info', message, options),
    }),
    [show],
  )

  return (
    <ToastContext value={api}>
      {children}
      {/*
        On phones above the shoppers' tab bar, so it never hides a toast; from `md` (no tab bar) toasts use the bottom
        right corner.
      */}
      <div className="pointer-events-none fixed inset-x-0 bottom-[var(--tabbar-h,0px)] z-[60] flex justify-center p-4 sm:justify-end md:bottom-0">
        <ol aria-label="Notifications" className="flex w-full max-w-sm flex-col gap-2.5">
          {toasts.map((toast) => (
            <Toast key={toast.id} toast={toast} onDismiss={dismiss} />
          ))}
        </ol>
      </div>
    </ToastContext>
  )
}
