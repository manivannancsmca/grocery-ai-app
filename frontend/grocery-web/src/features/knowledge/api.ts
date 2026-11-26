import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'

/**
 * The help center (knowledge base) the assistants answer policy and how-to questions from — the admin side
 * (docs/rag/RAG-API-REFERENCE.md). Every admin may look; SUPER_ADMIN and OPS_ADMIN change documents.
 */

export const KNOWLEDGE_CATEGORIES = ['POLICIES', 'APP_GUIDE', 'PRODUCT_GUIDE', 'VENDOR', 'DELIVERY', 'SUPPORT'] as const
export type KnowledgeCategory = (typeof KNOWLEDGE_CATEGORIES)[number]

export const CATEGORY_LABELS: Record<KnowledgeCategory, string> = {
  POLICIES: 'Policies',
  APP_GUIDE: 'Using the app',
  PRODUCT_GUIDE: 'Product guides',
  VENDOR: 'Vendors & restaurants',
  DELIVERY: 'Delivery',
  SUPPORT: 'Customer support',
}

export const KNOWLEDGE_AUDIENCES = ['CUSTOMER', 'VENDOR', 'RESTAURANT_OWNER', 'DELIVERY_PARTNER', 'STAFF'] as const
export type KnowledgeAudience = (typeof KNOWLEDGE_AUDIENCES)[number]

export const AUDIENCE_LABELS: Record<KnowledgeAudience, string> = {
  CUSTOMER: 'Customers',
  VENDOR: 'Vendors',
  RESTAURANT_OWNER: 'Restaurants',
  DELIVERY_PARTNER: 'Delivery partners',
  STAFF: 'Staff',
}

export const DOCUMENT_STATUSES = ['UPLOADED', 'PROCESSING', 'COMPLETED', 'FAILED', 'DELETED'] as const
export type DocumentStatus = (typeof DOCUMENT_STATUSES)[number]

/** Accepted files (the server checks the content too). */
export const ACCEPTED_FILES = '.pdf,.docx,.txt,.md,.markdown,.html,.htm'

export interface KnowledgeDocument {
  id: string
  title: string
  category: KnowledgeCategory | (string & {})
  audiences: string[]
  fileName: string
  format: string
  sizeBytes: number
  /** Files uploaded for it (1 = the first). */
  contentVersion: number
  status: DocumentStatus | (string & {})
  /** The assistants can use it now (while a new version is indexed or failed, the previous one is still used). */
  searchable: boolean
  chunkCount: number
  embeddingModel: string | null
  failureReason: string | null
  createdBy: { id: string; name: string }
  updatedBy: { id: string; name: string }
  createdAt: string
  updatedAt: string
  processedAt: string | null
}

export interface KnowledgeDocumentParams {
  status?: string
  category?: string
  page?: number
  size?: number
}

export interface KnowledgeUpload {
  file: File
  title?: string
  category?: string
  audiences?: string[]
}

/** {@code changed}: false when the same file (and details) was already there — nothing to index. */
export interface UploadResult {
  document: KnowledgeDocument
  changed: boolean
}

export interface KnowledgeSearchRequest {
  question: string
  audience?: string
  category?: string
  topK?: number
}

export interface KnowledgeSearchResult {
  /** FOUND, or NO_MATCH (the assistant then says it found no verified information). */
  status: 'FOUND' | 'NO_MATCH' | (string & {})
  results: { documentId: string; title: string; category: string; text: string; similarity: number }[]
}

const BASE = '/admin/ai/knowledge'

function form(upload: KnowledgeUpload): FormData {
  const body = new FormData()
  body.append('file', upload.file, upload.file.name)
  if (upload.title?.trim()) body.append('title', upload.title.trim())
  if (upload.category) body.append('category', upload.category)
  for (const audience of upload.audiences ?? []) body.append('audiences', audience)
  return body
}

export async function listKnowledgeDocuments(params: KnowledgeDocumentParams): Promise<PageResponse<KnowledgeDocument>> {
  return (await httpClient.get<PageResponse<KnowledgeDocument>>(`${BASE}/documents`, { params: cleanParams(params) })).data
}

export async function uploadKnowledgeDocument(upload: KnowledgeUpload): Promise<UploadResult> {
  const response = await httpClient.post<KnowledgeDocument>(`${BASE}/documents`, form(upload), { timeout: 60_000 })
  return { document: response.data, changed: response.status === 202 }
}

export async function replaceKnowledgeDocument(id: string, upload: KnowledgeUpload): Promise<UploadResult> {
  const response = await httpClient.put<KnowledgeDocument>(`${BASE}/documents/${segment(id)}`, form(upload), { timeout: 60_000 })
  return { document: response.data, changed: response.status === 202 }
}

export async function reindexKnowledgeDocument(id: string): Promise<KnowledgeDocument> {
  return (await httpClient.post<KnowledgeDocument>(`${BASE}/documents/${segment(id)}/reindex`)).data
}

export async function deleteKnowledgeDocument(id: string): Promise<void> {
  await httpClient.delete(`${BASE}/documents/${segment(id)}`)
}

export async function searchKnowledge(request: KnowledgeSearchRequest): Promise<KnowledgeSearchResult> {
  return (await httpClient.post<KnowledgeSearchResult>(`${BASE}/search`, request)).data
}

export const knowledgeKeys = {
  all: ['knowledge'] as const,
  lists: () => [...knowledgeKeys.all, 'documents'] as const,
  list: (params: KnowledgeDocumentParams) => [...knowledgeKeys.lists(), params] as const,
}

/** A document still waiting or being indexed: the list refreshes itself until it is done. */
export function isIndexing(document: KnowledgeDocument): boolean {
  return document.status === 'UPLOADED' || document.status === 'PROCESSING'
}

export function useKnowledgeDocuments(params: KnowledgeDocumentParams) {
  return useQuery({
    queryKey: knowledgeKeys.list(params),
    queryFn: () => listKnowledgeDocuments(params),
    placeholderData: keepPreviousData,
    refetchInterval: (query) => (query.state.data?.content.some(isIndexing) ? 4000 : false),
  })
}

function useKnowledgeMutation<TArgs, TResult>(run: (args: TArgs) => Promise<TResult>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: run,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: knowledgeKeys.lists() }),
  })
}

export function useUploadKnowledgeDocument() {
  return useKnowledgeMutation(uploadKnowledgeDocument)
}

export function useReplaceKnowledgeDocument() {
  return useKnowledgeMutation(({ id, upload }: { id: string; upload: KnowledgeUpload }) => replaceKnowledgeDocument(id, upload))
}

export function useReindexKnowledgeDocument() {
  return useKnowledgeMutation(reindexKnowledgeDocument)
}

export function useDeleteKnowledgeDocument() {
  return useKnowledgeMutation(deleteKnowledgeDocument)
}

export function useKnowledgeSearch() {
  return useMutation({ mutationFn: searchKnowledge })
}
