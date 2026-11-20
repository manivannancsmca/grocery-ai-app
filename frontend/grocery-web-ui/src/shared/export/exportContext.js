import { createContext, useContext } from 'react'

/** @typedef {import('./exportApi').ExportFormat} ExportFormat */
/** @typedef {import('./exportApi').ExportParams} ExportParams */

/**
 * An export the browser is downloading now.
 * @typedef {object} ExportJob
 * @property {number} id
 * @property {string} path The export endpoint: one export per list at a time.
 * @property {string} label What is exported, in words: "orders".
 * @property {ExportFormat} format
 * @property {number} received Bytes received so far.
 * @property {number | null} expectedRows Rows the list had when the export started (the page's own count), if known.
 */

/**
 * @typedef {object} StartExport
 * @property {string} path
 * @property {ExportParams} params
 * @property {ExportFormat} format
 * @property {string} label
 * @property {number | null} [expectedRows]
 */

/**
 * @typedef {object} ExportsApi
 * @property {readonly ExportJob[]} jobs
 * @property {(request: StartExport) => void} start Starts a download; it goes on while the user moves to other pages, and ends with a toast.
 * @property {(id: number) => void} cancel
 */

export const ExportContext = createContext(null)

/** @returns {ExportsApi} */
export function useExports() {
  const context = useContext(ExportContext)
  if (!context) {
    throw new Error('useExports must be used inside <ExportProvider>')
  }
  return context
}
