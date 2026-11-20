import { createContext } from 'react'

/** @typedef {'success' | 'error' | 'warning' | 'info'} ToastTone */

/**
 * @typedef {object} ToastOptions
 * @property {string} [description] Optional second line (e.g. the correlation reference of a failed request).
 * @property {number} [durationMs] Milliseconds before auto-dismiss; errors stay a little longer by default.
 */

/**
 * @typedef {object} ToastApi
 * @property {(tone: ToastTone, message: string, options?: ToastOptions) => void} show
 * @property {(message: string, options?: ToastOptions) => void} success
 * @property {(message: string, options?: ToastOptions) => void} error
 * @property {(message: string, options?: ToastOptions) => void} warning
 * @property {(message: string, options?: ToastOptions) => void} info
 */

export const ToastContext = createContext(null)
