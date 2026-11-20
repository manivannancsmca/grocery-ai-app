import { createContext, useContext } from 'react'
import type { ExportFormat, ExportParams } from './exportApi'

/** An export the browser is downloading now. */
export interface ExportJob {
  id: number
  /** The export endpoint: one export per list at a time. */
  path: string
  /** What is exported, in words: "orders". */
  label: string
  format: ExportFormat
  /** Bytes received so far. */
  received: number
  /** Rows the list had when the export started (the page's own count), if known. */
  expectedRows: number | null
}

export interface StartExport {
  path: string
  params: ExportParams
  format: ExportFormat
  label: string
  expectedRows?: number | null
}

export interface ExportsApi {
  jobs: readonly ExportJob[]
  /** Starts a download; it goes on while the user moves to other pages, and ends with a toast. */
  start: (request: StartExport) => void
  cancel: (id: number) => void
}

export const ExportContext = createContext<ExportsApi | null>(null)

export function useExports(): ExportsApi {
  const context = useContext(ExportContext)
  if (!context) {
    throw new Error('useExports must be used inside <ExportProvider>')
  }
  return context
}
