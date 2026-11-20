import { ActionMenu } from '../components/ui/ActionMenu'
import { Spinner } from '../components/ui/Spinner'
import { Icon } from '../icons'
import { formatCount } from '../utils/format'
import { exportSortParam, PDF_MAX_ROWS } from './exportApi'
import { useExports } from './exportContext'

/** @typedef {import('../components/ui/DataTable').TableSort} TableSort */
/** @typedef {import('./exportApi').ExportParams} ExportParams */

/**
 * @typedef {object} ExportButtonProps
 * @property {string} path The export endpoint, relative to `/api` (the list's path + `/export`), e.g. `/admin/orders/export`.
 * @property {ExportParams} [params] The list's current filters, named as the list endpoint takes them (page and size are not sent).
 * @property {string} label What the file holds, in words: "orders".
 * @property {number} [total] How many rows match the filters now (the list's `totalElements`): shown, and a PDF over its limit is refused.
 * @property {TableSort | null} [sort] The table's column sort, and the column keys the server can order the export by.
 * @property {readonly string[]} [sortable]
 * @property {string} [scopeNote] What the file holds, when "rows that match your filters" does not say it (e.g. a board without filters).
 * @property {string} [className]
 */

/**
 * "Export" on a list page: CSV, Excel or PDF of **every** row matching the page's filters (and in its order), not
 * only the page on screen. The server builds the file from the same query as the list, with the caller's own access
 * rules; this button only names the list and passes the filters. The download runs in `ExportProvider` (progress,
 * cancel, a toast at the end), so it is not lost when the user moves to another page.
 * @param {ExportButtonProps} props
 */
export function ExportButton({ path, params = {}, label, total, sort, sortable = [], scopeNote, className }) {
  const { jobs, start } = useExports()
  const running = jobs.some((job) => job.path === path)
  const sortParam = exportSortParam(sort, sortable)
  const pdfTooLarge = total !== undefined && total > PDF_MAX_ROWS

  const run = (format) =>
    start({ path, params: { ...params, sort: sortParam }, format, label, expectedRows: total ?? null })

  const items = [
    { label: 'CSV (.csv)', hint: 'For Excel, Google Sheets or any tool', icon: 'list', onSelect: () => run('csv') },
    {
      label: 'Excel (.xlsx)',
      hint: 'Numbers and dates ready to sort and sum',
      icon: 'spreadsheet',
      onSelect: () => run('xlsx'),
    },
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
