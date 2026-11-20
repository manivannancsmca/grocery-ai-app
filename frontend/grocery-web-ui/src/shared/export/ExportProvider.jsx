import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError } from '../api/problemDetail'
import { useToast } from '../components/toast/useToast'
import { Icon } from '../icons'
import { formatCount } from '../utils/format'
import {
  downloadExport,
  EXPORT_FORMAT_NAMES,
  ExportCancelled,
  exportErrorMessage,
  formatBytes,
  saveFile,
} from './exportApi'
import { ExportContext } from './exportContext'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../icons').IconName} IconName */
/** @typedef {import('./exportApi').ExportFormat} ExportFormat */
/** @typedef {import('./exportContext').ExportJob} ExportJob */

/** @type {Record<ExportFormat, IconName>} */
const FORMAT_ICONS = { csv: 'list', xlsx: 'spreadsheet', pdf: 'document' }

let nextId = 1

/**
 * @param {string} text
 * @returns {string}
 */
function capitalise(text) {
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/**
 * One running export: what, how far, and a way to stop it.
 * @param {{ job: ExportJob, onCancel: () => void }} props
 */
function ExportCard({ job, onCancel }) {
  const progress = job.received > 0 ? `${formatBytes(job.received)} received` : 'Preparing the file…'
  const rows =
    job.expectedRows === null ? null : `${formatCount(job.expectedRows)} ${job.expectedRows === 1 ? 'row' : 'rows'}`
  return (
    <li className="animate-pop-in pointer-events-auto relative overflow-hidden rounded-2xl bg-white p-3.5 shadow-xl ring-1 shadow-slate-900/10 ring-slate-200">
      <div className="flex items-center gap-3">
        <span
          aria-hidden="true"
          className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-emerald-50 text-emerald-700 ring-1 ring-emerald-100"
        >
          <Icon name={FORMAT_ICONS[job.format]} className="size-5" />
        </span>
        <div role="status" className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold text-slate-900">
            Exporting {job.label} · {EXPORT_FORMAT_NAMES[job.format]}
          </p>
          <p className="truncate text-xs text-slate-600 tabular-nums">{rows ? `${progress} · ${rows}` : progress}</p>
        </div>
        <button
          type="button"
          aria-label={`Cancel exporting ${job.label}`}
          onClick={onCancel}
          className="rounded-lg px-2.5 py-1.5 text-xs font-semibold text-slate-600 ring-1 ring-slate-200 transition-colors ring-inset hover:bg-red-50 hover:text-red-700 hover:ring-red-200 focus-visible:outline-2 focus-visible:outline-emerald-600"
        >
          Cancel
        </button>
      </div>
      {/* The size of the file is not known in advance: a moving bar says "working". */}
      <div aria-hidden="true" className="absolute inset-x-0 bottom-0 h-1 overflow-hidden bg-emerald-50">
        <div className="animate-shimmer h-full w-full bg-[linear-gradient(90deg,transparent,#10b981,transparent)] bg-[length:40%_100%] bg-no-repeat" />
      </div>
    </li>
  )
}

/**
 * Runs the app's exports (see `ExportButton`): each download goes on while the user moves around the app, shows
 * its progress in a small tray under the top bar (with Cancel), and ends with a toast — the file saved, or why not.
 * @param {{ children: ReactNode }} props
 */
export function ExportProvider({ children }) {
  const toast = useToast()
  const [jobs, setJobs] = useState([])
  const controllers = useRef(new Map())

  // Leaving the app (sign-out unmounts the provider) stops the downloads.
  useEffect(() => {
    const running = controllers.current
    return () => running.forEach((controller) => controller.abort())
  }, [])

  const start = useCallback(
    (request) => {
      const id = nextId++
      const controller = new AbortController()
      controllers.current.set(id, controller)
      const job = {
        id,
        path: request.path,
        label: request.label,
        format: request.format,
        received: 0,
        expectedRows: request.expectedRows ?? null,
      }
      setJobs((current) => [...current, job])
      const finish = () => {
        controllers.current.delete(id)
        setJobs((current) => current.filter((candidate) => candidate.id !== id))
      }
      downloadExport(
        { path: request.path, params: request.params, format: request.format },
        {
          signal: controller.signal,
          onProgress: (received) =>
            setJobs((current) =>
              current.map((candidate) => (candidate.id === id ? { ...candidate, received } : candidate)),
            ),
        },
      ).then(
        (file) => {
          finish()
          saveFile(file.blob, file.fileName)
          const rows = file.rows === null ? '' : ` · ${formatCount(file.rows)} ${file.rows === 1 ? 'row' : 'rows'}`
          toast.success(`${capitalise(request.label)} exported`, { description: `${file.fileName}${rows}` })
        },
        (error) => {
          finish()
          if (error instanceof ExportCancelled) {
            toast.info(`Export of ${request.label} cancelled`)
            return
          }
          const reference = error instanceof ApiError && error.correlationId ? ` Reference: ${error.correlationId}` : ''
          toast.error(`Could not export ${request.label}`, { description: `${exportErrorMessage(error)}${reference}` })
        },
      )
    },
    [toast],
  )

  const cancel = useCallback((id) => controllers.current.get(id)?.abort(), [])

  const api = useMemo(() => ({ jobs, start, cancel }), [jobs, start, cancel])

  return (
    <ExportContext value={api}>
      {children}
      {jobs.length > 0 && (
        <div className="pointer-events-none fixed inset-x-0 top-[4.75rem] z-[55] flex justify-center px-4 sm:justify-end print:hidden">
          <ol aria-label="Exports in progress" className="flex w-full max-w-sm flex-col gap-2">
            {jobs.map((job) => (
              <ExportCard key={job.id} job={job} onCancel={() => cancel(job.id)} />
            ))}
          </ol>
        </div>
      )}
    </ExportContext>
  )
}
