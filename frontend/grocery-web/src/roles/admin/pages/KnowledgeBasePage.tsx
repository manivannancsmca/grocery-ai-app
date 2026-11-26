import { type FormEvent, useId, useRef, useState } from 'react'
import {
  ACCEPTED_FILES,
  AUDIENCE_LABELS,
  CATEGORY_LABELS,
  DOCUMENT_STATUSES,
  type DocumentStatus,
  isIndexing,
  KNOWLEDGE_AUDIENCES,
  KNOWLEDGE_CATEGORIES,
  type KnowledgeCategory,
  type KnowledgeDocument,
  type KnowledgeSearchResult,
  type UploadResult,
  useDeleteKnowledgeDocument,
  useKnowledgeDocuments,
  useKnowledgeSearch,
  useReindexKnowledgeDocument,
  useReplaceKnowledgeDocument,
  useUploadKnowledgeDocument,
} from '../../../features/knowledge/api'
import { ApiError, errorMessage } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import { useAuth } from '../../../shared/auth/useAuth'
import {
  Alert,
  Badge,
  type BadgeTone,
  Button,
  Card,
  Checkbox,
  type Column,
  ConfirmDialog,
  DataTable,
  EmptyState,
  ErrorState,
  FilterBar,
  PageHeader,
  Pagination,
  SelectField,
  Spinner,
  TextField,
} from '../../../shared/components/ui'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { formatDateTime, humanizeToken } from '../../../shared/utils/format'

const STATUS_LABELS: Record<DocumentStatus, string> = {
  UPLOADED: 'Waiting',
  PROCESSING: 'Indexing',
  COMPLETED: 'Ready',
  FAILED: 'Failed',
  DELETED: 'Deleted',
}
const STATUS_TONES: Record<DocumentStatus, BadgeTone> = {
  UPLOADED: 'amber',
  PROCESSING: 'blue',
  COMPLETED: 'green',
  FAILED: 'red',
  DELETED: 'gray',
}

function statusLabel(status: string): string {
  return STATUS_LABELS[status as DocumentStatus] ?? humanizeToken(status)
}

function categoryLabel(category: string): string {
  return CATEGORY_LABELS[category as KnowledgeCategory] ?? humanizeToken(category)
}

function audienceLabel(audience: string): string {
  return AUDIENCE_LABELS[audience as keyof typeof AUDIENCE_LABELS] ?? humanizeToken(audience)
}

function fileSize(bytes: number): string {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * `/admin/ai/knowledge` — the help center the assistants answer policy and how-to questions from
 * (docs/rag/RAG-API-REFERENCE.md). Every admin may look and test the search; SUPER_ADMIN and OPS_ADMIN add, replace,
 * reindex and delete articles. Indexing runs in the background; the list refreshes itself until it is done.
 */
export function KnowledgeBasePage() {
  const { can } = useAuth()
  const canManage = can('knowledge.manage')
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const statusParam = searchParams.get('status') ?? ''
  const status = (DOCUMENT_STATUSES as readonly string[]).includes(statusParam) ? statusParam : undefined
  const categoryParam = searchParams.get('category') ?? ''
  const category = (KNOWLEDGE_CATEGORIES as readonly string[]).includes(categoryParam) ? categoryParam : undefined
  const page = pageFromParam(searchParams.get('page'))
  const documents = useKnowledgeDocuments({ status, category, page, size: DEFAULT_PAGE_SIZE })
  const switchedOff = documents.error instanceof ApiError && documents.error.errorCode === 'KNOWLEDGE_BASE_DISABLED'

  const columns: Column<KnowledgeDocument>[] = [
    {
      key: 'title',
      header: 'Article',
      sortValue: (document) => document.title,
      cell: (document) => (
        <div className="min-w-0">
          <p className="font-medium break-words text-slate-900">{document.title}</p>
          <p className="mt-0.5 text-xs break-all text-slate-500">
            {document.fileName} · {document.format} · {fileSize(document.sizeBytes)}
            {document.contentVersion > 1 && ` · version ${document.contentVersion}`}
          </p>
        </div>
      ),
    },
    {
      key: 'category',
      header: 'Category',
      hideOnMobile: true,
      sortValue: (document) => categoryLabel(document.category),
      cell: (document) => <span className="text-sm text-slate-700">{categoryLabel(document.category)}</span>,
    },
    {
      key: 'readers',
      header: 'Readers',
      hideOnMobile: true,
      cell: (document) => <span className="text-sm text-slate-700">{document.audiences.map(audienceLabel).join(', ')}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (document) => statusLabel(document.status),
      cell: (document) => (
        <div className="flex flex-col items-start gap-1">
          <Badge tone={STATUS_TONES[document.status as DocumentStatus] ?? 'gray'} dot>
            {statusLabel(document.status)}
          </Badge>
          {document.status === 'COMPLETED' && <span className="text-xs text-slate-500">{document.chunkCount} passages</span>}
          {document.failureReason && <p className="max-w-64 text-xs break-words text-red-700">{document.failureReason}</p>}
          {document.searchable && document.status !== 'COMPLETED' && (
            <p className="max-w-64 text-xs text-slate-500">The previous version is still used until this one is ready.</p>
          )}
        </div>
      ),
    },
    {
      key: 'updated',
      header: 'Updated',
      hideOnMobile: true,
      sortValue: (document) => document.updatedAt,
      cell: (document) => (
        <span className="text-xs whitespace-nowrap text-slate-600">
          {formatDateTime(document.updatedAt)}
          <br />
          {document.updatedBy.name}
        </span>
      ),
    },
    ...(canManage
      ? [{ key: 'actions', header: <span className="sr-only">Actions</span>, cell: (document: KnowledgeDocument) => <DocumentActions document={document} /> }]
      : []),
  ]

  return (
    <div className="space-y-5">
      <PageHeader
        icon="menuBook"
        title="Help center"
        description="Articles the assistants answer policy and how-to questions from, and show as the source. Live facts — stock, prices, carts, orders, payments and the fees you set — always come from the app itself."
      />
      {!canManage && (
        <Alert tone="info" role="status" title="View only">
          Only super admins and operations admins can change the help center. You can still test what the assistants find.
        </Alert>
      )}
      {switchedOff ? (
        <EmptyState
          size="lg"
          title="The help center is switched off"
          description="Turn the knowledge base on in the AI service (AI_KNOWLEDGE_ENABLED) to add articles; the assistants work as before without it."
        />
      ) : (
        <>
          {canManage && <UploadCard />}
          <FilterBar activeCount={(status ? 1 : 0) + (category ? 1 : 0)} onClear={() => updateParams({ status: null, category: null, page: null })}>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <SelectField label="Status" value={status ?? ''} onChange={(event) => updateParams({ status: event.target.value, page: null })}>
                <option value="">In use (not deleted)</option>
                {DOCUMENT_STATUSES.map((value) => (
                  <option key={value} value={value}>
                    {STATUS_LABELS[value]}
                  </option>
                ))}
              </SelectField>
              <SelectField label="Category" value={category ?? ''} onChange={(event) => updateParams({ category: event.target.value, page: null })}>
                <option value="">All categories</option>
                {KNOWLEDGE_CATEGORIES.map((value) => (
                  <option key={value} value={value}>
                    {CATEGORY_LABELS[value]}
                  </option>
                ))}
              </SelectField>
            </div>
          </FilterBar>
          {documents.isPending ? (
            <div className="flex justify-center rounded-2xl border border-slate-200/80 bg-white py-12 shadow-sm">
              <Spinner size="lg" label="Loading the help center" />
            </div>
          ) : documents.isError ? (
            <ErrorState
              title="Could not load the help center"
              message={documents.error.message}
              correlationId={documents.error instanceof ApiError ? documents.error.correlationId : null}
              onRetry={() => void documents.refetch()}
            />
          ) : (
            <div>
              <DataTable
                caption="Help-center articles"
                columns={columns}
                rows={documents.data.content}
                getRowKey={(document) => document.id}
                fetching={documents.isFetching && documents.isPlaceholderData}
                rowClassName={(document) => (document.status === 'FAILED' ? 'bg-red-50/40' : isIndexing(document) ? 'bg-sky-50/40' : undefined)}
                empty={
                  <EmptyState
                    title="No articles"
                    description={status || category ? 'Try different filters.' : 'Add policies, how-tos and FAQs so the assistants can answer from them.'}
                  />
                }
              />
              <Pagination
                page={documents.data.page}
                size={documents.data.size}
                totalElements={documents.data.totalElements}
                totalPages={documents.data.totalPages}
                disabled={documents.isFetching}
                onPageChange={(next) => updateParams({ page: pageToParam(next) })}
              />
            </div>
          )}
          <SearchTestCard />
        </>
      )}
    </div>
  )
}

/** Adds an article: the file, an optional title, its category and who may read it. */
function UploadCard() {
  const upload = useUploadKnowledgeDocument()
  const fileInput = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [title, setTitle] = useState('')
  const [category, setCategory] = useState<KnowledgeCategory>('POLICIES')
  const [audiences, setAudiences] = useState<string[]>(['CUSTOMER', 'STAFF'])
  const [result, setResult] = useState<UploadResult | null>(null)
  const readersId = useId()

  function toggle(audience: string) {
    setAudiences((current) => (current.includes(audience) ? current.filter((value) => value !== audience) : [...current, audience]))
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!file || audiences.length === 0) return
    setResult(null)
    upload.mutate(
      { file, title, category, audiences },
      {
        onSuccess: (uploaded) => {
          setResult(uploaded)
          setFile(null)
          setTitle('')
          if (fileInput.current) fileInput.current.value = ''
        },
      },
    )
  }

  return (
    <Card
      icon="document"
      title="Add an article"
      description="PDF, Word (.docx), text, Markdown or HTML, up to 5 MB. Write policies in words: amounts you set in Platform settings are read live by the assistants."
    >
      <form className="space-y-4" onSubmit={submit}>
        <div className="grid gap-3 md:grid-cols-3">
          <TextField
            ref={fileInput}
            label="File"
            type="file"
            accept={ACCEPTED_FILES}
            required
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
          <TextField label="Title" value={title} maxLength={200} hint="Optional: the file name otherwise" onChange={(event) => setTitle(event.target.value)} />
          <SelectField label="Category" value={category} onChange={(event) => setCategory(event.target.value as KnowledgeCategory)}>
            {KNOWLEDGE_CATEGORIES.map((value) => (
              <option key={value} value={value}>
                {CATEGORY_LABELS[value]}
              </option>
            ))}
          </SelectField>
        </div>
        <fieldset aria-describedby={readersId}>
          <legend className="text-sm font-medium text-slate-700">Who may read it</legend>
          <p id={readersId} className="text-xs text-slate-500">
            The assistants only use it for people in these groups.
          </p>
          <div className="mt-2 flex flex-wrap gap-x-5 gap-y-2">
            {KNOWLEDGE_AUDIENCES.map((audience) => (
              <label key={audience} className="flex items-center gap-2 text-sm text-slate-700">
                <Checkbox checked={audiences.includes(audience)} onChange={() => toggle(audience)} />
                {AUDIENCE_LABELS[audience]}
              </label>
            ))}
          </div>
        </fieldset>
        {upload.isError && (
          <Alert tone="danger" role="alert">
            {errorMessage(upload.error)}
          </Alert>
        )}
        {result && (
          <Alert tone="success" role="status">
            {result.changed
              ? `“${result.document.title}” was added. It is being indexed and the assistants can use it in a minute.`
              : `This file is already in the help center as “${result.document.title}”. Nothing was added.`}
          </Alert>
        )}
        <Button type="submit" disabled={!file || audiences.length === 0 || upload.isPending}>
          {upload.isPending ? 'Adding…' : 'Add article'}
        </Button>
      </form>
    </Card>
  )
}

/** Upload a new version, index again, or delete one article. */
function DocumentActions({ document }: { document: KnowledgeDocument }) {
  const replace = useReplaceKnowledgeDocument()
  const reindex = useReindexKnowledgeDocument()
  const remove = useDeleteKnowledgeDocument()
  const newVersion = useRef<HTMLInputElement>(null)
  const [confirming, setConfirming] = useState(false)
  if (document.status === 'DELETED') return null
  const error = replace.error ?? reindex.error
  return (
    <div className="flex flex-col items-end gap-1.5">
      <div className="flex flex-wrap justify-end gap-1.5">
        <input
          ref={newVersion}
          type="file"
          accept={ACCEPTED_FILES}
          className="sr-only"
          aria-label={`New version of “${document.title}”`}
          onChange={(event) => {
            const file = event.target.files?.[0]
            if (file) replace.mutate({ id: document.id, upload: { file } })
            event.target.value = ''
          }}
        />
        <Button size="sm" variant="secondary" disabled={replace.isPending} onClick={() => newVersion.current?.click()}>
          New version
        </Button>
        <Button size="sm" variant="secondary" disabled={reindex.isPending || isIndexing(document)} onClick={() => reindex.mutate(document.id)}>
          Reindex
        </Button>
        <Button size="sm" variant="danger" onClick={() => setConfirming(true)}>
          Delete
        </Button>
      </div>
      {error && <p className="max-w-56 text-right text-xs text-red-700">{errorMessage(error)}</p>}
      <ConfirmDialog
        open={confirming}
        tone="danger"
        title={`Delete “${document.title}”?`}
        confirmLabel="Delete article"
        pending={remove.isPending}
        error={remove.isError ? errorMessage(remove.error) : null}
        onConfirm={() => remove.mutate(document.id, { onSuccess: () => setConfirming(false) })}
        onClose={() => setConfirming(false)}
      >
        The assistants stop using it at once. This cannot be undone; upload the file again to bring it back.
      </ConfirmDialog>
    </div>
  )
}

/** What would the assistants find for a question, as a given reader? */
function SearchTestCard() {
  const search = useKnowledgeSearch()
  const [question, setQuestion] = useState('')
  const [audience, setAudience] = useState<string>('CUSTOMER')

  function submit(event: FormEvent) {
    event.preventDefault()
    if (question.trim()) search.mutate({ question: question.trim(), audience })
  }

  return (
    <Card icon="search" title="Test the search" description="Ask a question as a customer, vendor or partner would, and see which passages the assistant would be given.">
      <form className="grid gap-3 md:grid-cols-[1fr_14rem_auto] md:items-end" onSubmit={submit}>
        <TextField label="Question" value={question} maxLength={1000} placeholder="Can I cancel a paid order?" onChange={(event) => setQuestion(event.target.value)} />
        <SelectField label="Asked by" value={audience} onChange={(event) => setAudience(event.target.value)}>
          {KNOWLEDGE_AUDIENCES.map((value) => (
            <option key={value} value={value}>
              {AUDIENCE_LABELS[value]}
            </option>
          ))}
        </SelectField>
        <Button type="submit" disabled={!question.trim() || search.isPending}>
          {search.isPending ? 'Searching…' : 'Search'}
        </Button>
      </form>
      {search.isError && (
        <Alert tone="danger" role="alert" className="mt-4">
          {errorMessage(search.error)}
        </Alert>
      )}
      {search.data && <SearchResults result={search.data} />}
    </Card>
  )
}

function SearchResults({ result }: { result: KnowledgeSearchResult }) {
  if (result.status !== 'FOUND' || result.results.length === 0) {
    return (
      <Alert tone="info" role="status" className="mt-4">
        No article matches. The assistant would say it couldn&apos;t find verified information and suggest customer support.
      </Alert>
    )
  }
  return (
    <ol aria-label="Passages found" className="mt-4 space-y-3">
      {result.results.map((passage, index) => (
        <li key={`${passage.documentId}-${index}`} className="rounded-xl border border-slate-200 bg-slate-50/60 p-3">
          <p className="flex flex-wrap items-center justify-between gap-2 text-sm">
            <span className="font-medium text-slate-900">{passage.title}</span>
            <span className="text-xs text-slate-500 tabular-nums">{Math.round(passage.similarity * 100)}% similar</span>
          </p>
          <p className="mt-1.5 text-sm whitespace-pre-line text-slate-700">{passage.text.length > 400 ? `${passage.text.slice(0, 400)}…` : passage.text}</p>
        </li>
      ))}
    </ol>
  )
}
