import { isAxiosError } from 'axios'
import { httpClient } from '../api/httpClient'
import { ApiError, errorMessage, isProblemDetail } from '../api/problemDetail'
import { cleanParams } from '../api/types'

/** @typedef {import('../components/ui/DataTable').TableSort} TableSort */

/**
 * The file formats every export endpoint offers (`?format=`).
 * @typedef {'csv' | 'xlsx' | 'pdf'} ExportFormat
 */

/** @type {Record<ExportFormat, string>} */
export const EXPORT_FORMAT_NAMES = { csv: 'CSV', xlsx: 'Excel', pdf: 'PDF' }

/** The most rows a PDF may have: the services' `grocery.export.pdf-max-rows` (a larger PDF is refused). */
export const PDF_MAX_ROWS = 5_000

/**
 * A list's filters as the list endpoint takes them; empty values are not sent.
 * @typedef {Record<string, string | number | boolean | null | undefined>} ExportParams
 */

/**
 * @typedef {object} ExportRequest
 * @property {string} path The export endpoint, relative to `/api`: `/admin/orders/export`.
 * @property {ExportParams} params
 * @property {ExportFormat} format
 */

/**
 * @typedef {object} DownloadedFile
 * @property {Blob} blob
 * @property {string} fileName
 * @property {number | null} rows Rows in the file, from the `X-Export-Rows` header (null when the server did not say).
 */

/** The user (or a page leaving) stopped the download. */
export class ExportCancelled extends Error {
  constructor() {
    super('The export was cancelled.')
    this.name = 'ExportCancelled'
  }
}

/**
 * Downloads an export through the shared client (so the session's token and its refresh apply; a plain link would
 * carry no token). The server streams the whole filtered list — every page, not just the one on screen — and the
 * browser keeps the file as a Blob until it is saved. No timeout: a big export may take minutes, and the user can
 * cancel it.
 * @param {ExportRequest} request
 * @param {{ signal?: AbortSignal, onProgress?: (bytes: number) => void }} [options={}]
 * @returns {Promise<DownloadedFile>}
 */
export async function downloadExport(request, options = {}) {
  try {
    const response = await httpClient.get(request.path, {
      params: { ...cleanParams(request.params), format: request.format },
      responseType: 'blob',
      timeout: 0,
      signal: options.signal,
      onDownloadProgress: (event) => options.onProgress?.(event.loaded),
    })
    const data = response.data
    const blob = data instanceof Blob ? data : new Blob([typeof data === 'string' ? data : JSON.stringify(data)])
    const header = (name) => {
      const value = response.headers[name]
      return typeof value === 'string' ? value : null
    }
    const rows = Number.parseInt(header('x-export-rows') ?? '', 10)
    return {
      blob,
      fileName: fileNameFrom(header('content-disposition')) ?? `export.${request.format}`,
      rows: Number.isFinite(rows) ? rows : null,
    }
  } catch (error) {
    if (options.signal?.aborted) throw new ExportCancelled()
    throw await withProblemBody(error)
  }
}

/**
 * `attachment; filename="orders-2026-11-15-1030.csv"` → the name (only safe file-name characters kept).
 * @param {string | null} contentDisposition
 * @returns {string | null}
 */
export function fileNameFrom(contentDisposition) {
  const match = contentDisposition?.match(/filename="?([^";]+)"?/i)
  const name = match?.[1]?.trim().replace(/[^\w.-]/g, '_')
  return name ? name : null
}

/**
 * `?sort=` for the server: the column key and its direction, when the server can order by that column.
 * @param {TableSort | null | undefined} sort
 * @param {readonly string[]} sortable
 * @returns {string | undefined}
 */
export function exportSortParam(sort, sortable) {
  if (!sort || !sortable.includes(sort.key)) return undefined
  return `${sort.key},${sort.direction === 'ascending' ? 'asc' : 'desc'}`
}

/**
 * Hands the file to the browser as a download.
 * @param {Blob} blob
 * @param {string} fileName
 * @returns {void}
 */
export function saveFile(blob, fileName) {
  if (typeof URL.createObjectURL !== 'function') return
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.rel = 'noopener'
  document.body.appendChild(link)
  link.click()
  link.remove()
  // Some browsers start reading the file only after the click returns: keep the URL a little while.
  setTimeout(() => URL.revokeObjectURL?.(url), 10_000)
}

/**
 * An error answer to a `responseType: 'blob'` request carries its ProblemDetail as a Blob, which the shared client
 * cannot read synchronously: read it here, so the user sees the server's message (e.g. "This export has 12,000 rows;
 * the limit is 5,000. Choose CSV or Excel…").
 * @param {unknown} error
 * @returns {Promise<unknown>}
 */
async function withProblemBody(error) {
  if (!(error instanceof ApiError) || error.problem !== null) return error
  const cause = error.cause
  const body = isAxiosError(cause) ? cause.response?.data : undefined
  if (!(body instanceof Blob)) return error
  try {
    const parsed = JSON.parse(await blobText(body))
    if (!isProblemDetail(parsed)) return error
    const correlationId = typeof parsed.correlationId === 'string' ? parsed.correlationId : error.correlationId
    return new ApiError(parsed.detail ?? error.message, {
      kind: 'http',
      status: error.status,
      problem: parsed,
      correlationId,
      cause,
    })
  } catch {
    return error
  }
}

/**
 * A Blob's text (`Blob.text()` where the browser has it, else a FileReader).
 * @param {Blob} blob
 * @returns {Promise<string>}
 */
function blobText(blob) {
  if (typeof blob.text === 'function') return blob.text()
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '')
    reader.onerror = () => reject(reader.error ?? new Error('Could not read the error body'))
    reader.readAsText(blob)
  })
}

/**
 * What to tell the user when an export fails.
 * @param {unknown} error
 * @returns {string}
 */
export function exportErrorMessage(error) {
  if (error instanceof ApiError) {
    if (error.status === 403) return 'Your account cannot export this list.'
    if (error.kind === 'network' && error.status === null) {
      return 'The export stopped before it finished, so nothing was saved. Check your connection and try again.'
    }
    if (error.problem?.detail) return error.problem.detail
  }
  return errorMessage(error)
}

/**
 * "2.4 MB" / "640 KB" for the progress line.
 * @param {number} bytes
 * @returns {string}
 */
export function formatBytes(bytes) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
