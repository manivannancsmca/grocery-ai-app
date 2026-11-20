import { ActionMenu, type ActionMenuItem } from '../components/ui/ActionMenu'
import type { TableSort } from '../components/ui/DataTable'
import { Spinner } from '../components/ui/Spinner'
import { Icon } from '../icons'
import { formatCount } from '../utils/format'
import { type ExportFormat, type ExportParams, exportSortParam, PDF_MAX_ROWS } from './exportApi'
import { useExports } from './exportContext'

interface ExportButtonProps {
  /** The export endpoint, relative to `/api` (the list's path + `/export`), e.g. `/admin/orders/export`. */
  path: string
  /** The list's current filters, named as the list endpoint takes them (page and size are not sent). */
  params?: ExportParams
  /** What the file holds, in words: "orders". */
  label: string
  /** How many rows match the filters now (the list's `totalElements`): shown, and a PDF over its limit is refused. */
  total?: number
  /** The table's column sort, and the column keys the server can order the export by. */
  sort?: TableSort | null
  sortable?: readonly string[]
  /** What the file holds, when "rows that match your filters" does not say it (e.g. a board without filters). */
  scopeNote?: string
  className?: string
}

/**
 * "Export" on a list page: CSV, Excel or PDF of **every** row matching the page's filters (and in its order), not
 * only the page on screen. The server builds the file from the same query as the list, with the caller's own access
 * rules; this button only names the list and passes the filters. The download runs in `ExportProvider` (progress,
 * cancel, a toast at the end), so it is not lost when the user moves to another page.
 */
export function ExportButton({ path, params = {}, label, total, sort, sortable = [], scopeNote, className }: ExportButtonProps) {
  const { jobs, start } = useExports()
  const running = jobs.some((job) => job.path === path)
  const sortParam = exportSortParam(sort, sortable)
  const pdfTooLarge = total !== undefined && total > PDF_MAX_ROWS

  const run = (format: ExportFormat) =>
    start({ path, params: { ...params, sort: sortParam }, format, label, expectedRows: total ?? null })

  const items: ActionMenuItem[] = [
    { label: 'CSV (.csv)', hint: 'For Excel, Google Sheets or any tool', icon: 'list', onSelect: () => run('csv') },
    { label: 'Excel (.xlsx)', hint: 'Numbers and dates ready to sort and sum', icon: 'spreadsheet', onSelect: () => run('xlsx') },
    {
      label: 'PDF (.pdf)',
      hint: pdfTooLarge ? `Up to ${formatCount(PDF_MAX_ROWS)} rows: choose CSV or Excel` : 'To print or share',
      icon: 'document',
      disabled: pdfTooLarge,
      onSelect: () => run('pdf'),
    },
  ]

  const scope =
    scopeNote ??
    (total === undefined
      ? 'Every row that matches your filters, not just this page.'
      : total === 0
        ? 'Nothing matches your filters: the file will have the column headings only.'
        : total === 1
          ? 'The one row that matches your filters.'
          : `All ${formatCount(total)} rows that match your filters, not just this page.`)
  const order = sort && !sortParam ? ' The file keeps the list’s own order.' : ''

  return (
    <ActionMenu
      label={running ? `Exporting ${label}…` : `Export ${label}`}
      disabled={running}
      width={272}
      className={className}
      trigger={
        running ? (
          <>
            <Spinner size="sm" />
            Exporting…
          </>
        ) : (
          <>
            <Icon name="download" className="size-4" />
            Export
          </>
        )
      }
      items={items}
      footer={`${scope}${order}`}
    />
  )
}
