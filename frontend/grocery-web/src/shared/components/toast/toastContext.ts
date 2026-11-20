import { createContext } from 'react'

export type ToastTone = 'success' | 'error' | 'warning' | 'info'

export interface ToastOptions {
  /** Optional second line (e.g. the correlation reference of a failed request). */
  description?: string
  /** Milliseconds before auto-dismiss; errors stay a little longer by default. */
  durationMs?: number
}

export interface ToastApi {
  show: (tone: ToastTone, message: string, options?: ToastOptions) => void
  success: (message: string, options?: ToastOptions) => void
  error: (message: string, options?: ToastOptions) => void
  warning: (message: string, options?: ToastOptions) => void
  info: (message: string, options?: ToastOptions) => void
}

export const ToastContext = createContext<ToastApi | null>(null)
