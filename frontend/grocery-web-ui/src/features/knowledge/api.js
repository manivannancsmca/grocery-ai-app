import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */

/**
 * The help center (knowledge base) the assistants answer policy and how-to questions from — the admin side
 * (docs/rag/RAG-API-REFERENCE.md). Every admin may look; SUPER_ADMIN and OPS_ADMIN change documents.
 */

export const KNOWLEDGE_CATEGORIES = ['POLICIES', 'APP_GUIDE', 'PRODUCT_GUIDE', 'VENDOR', 'DELIVERY', 'SUPPORT']
/** @typedef {(typeof KNOWLEDGE_CATEGORIES)[number]} KnowledgeCategory */

/** @type {Record<KnowledgeCategory, string>} */
export const CATEGORY_LABELS = {
  POLICIES: 'Policies',
  APP_GUIDE: 'Using the app',
  PRODUCT_GUIDE: 'Product guides',
  VENDOR: 'Vendors & restaurants',
  DELIVERY: 'Delivery',
  SUPPORT: 'Customer support',
}

export const KNOWLEDGE_AUDIENCES = ['CUSTOMER', 'VENDOR', 'RESTAURANT_OWNER', 'DELIVERY_PARTNER', 'STAFF']
/** @typedef {(typeof KNOWLEDGE_AUDIENCES)[number]} KnowledgeAudience */

/** @type {Record<KnowledgeAudience, string>} */
export const AUDIENCE_LABELS = {
  CUSTOMER: 'Customers',
  VENDOR: 'Vendors',
  RESTAURANT_OWNER: 'Restaurants',
  DELIVERY_PARTNER: 'Delivery partners',
  STAFF: 'Staff',
}

export const DOCUMENT_STATUSES = ['UPLOADED', 'PROCESSING', 'COMPLETED', 'FAILED', 'DELETED']
/** @typedef {(typeof DOCUMENT_STATUSES)[number]} DocumentStatus */

/** Accepted files (the server checks the content too). */
export const ACCEPTED_FILES = '.pdf,.docx,.txt,.md,.markdown,.html,.htm'

/**
 * @typedef {object} KnowledgeDocument
 * @property {string} id
 * @property {string} title
 * @property {KnowledgeCategory | (string & {})} category
 * @property {string[]} audiences
 * @property {string} fileName
 * @property {string} format
 * @property {number} sizeBytes
 * @property {number} contentVersion Files uploaded for it (1 = the first).
 * @property {DocumentStatus | (string & {})} status
 * @property {boolean} searchable The assistants can use it now (while a new version is indexed or failed, the previous one is still used).
 * @property {number} chunkCount
 * @property {string | null} embeddingModel
 * @property {string | null} failureReason
 * @property {{ id: string, name: string }} createdBy
 * @property {{ id: string, name: string }} updatedBy
 * @property {string} createdAt
 * @property {string} updatedAt
 * @property {string | null} processedAt
 */

/**
 * @typedef {object} KnowledgeDocumentParams
 * @property {string} [status]
 * @property {string} [category]
 * @property {number} [page]
 * @property {number} [size]
 */

/**
 * @typedef {object} KnowledgeUpload
 * @property {File} file
 * @property {string} [title]
 * @property {string} [category]
 * @property {string[]} [audiences]
 */

/**
 * {@code changed}: false when the same file (and details) was already there — nothing to index.
 * @typedef {object} UploadResult
 * @property {KnowledgeDocument} document
 * @property {boolean} changed
 */

/**
 * @typedef {object} KnowledgeSearchRequest
 * @property {string} question
 * @property {string} [audience]
 * @property {string} [category]
 * @property {number} [topK]
 */

/**
 * @typedef {object} KnowledgeSearchResult
 * @property {'FOUND' | 'NO_MATCH' | (string & {})} status FOUND, or NO_MATCH (the assistant then says it found no verified information).
 * @property {{ documentId: string, title: string, category: string, text: string, similarity: number }[]} results
 */

const BASE = '/admin/ai/knowledge'

/**
 * @param {KnowledgeUpload} upload
 * @returns {FormData}
 */
function form(upload) {
  const body = new FormData()
  body.append('file', upload.file, upload.file.name)
  if (upload.title?.trim()) body.append('title', upload.title.trim())
  if (upload.category) body.append('category', upload.category)
  for (const audience of upload.audiences ?? []) body.append('audiences', audience)
  return body
}

/**
 * @param {KnowledgeDocumentParams} params
 * @returns {Promise<PageResponse<KnowledgeDocument>>}
 */
export async function listKnowledgeDocuments(params) {
  return (await httpClient.get(`${BASE}/documents`, { params: cleanParams(params) })).data
}

/**
 * @param {KnowledgeUpload} upload
 * @returns {Promise<UploadResult>}
 */
export async function uploadKnowledgeDocument(upload) {
  const response = await httpClient.post(`${BASE}/documents`, form(upload), { timeout: 60_000 })
  return { document: response.data, changed: response.status === 202 }
}

/**
 * @param {string} id
 * @param {KnowledgeUpload} upload
 * @returns {Promise<UploadResult>}
 */
export async function replaceKnowledgeDocument(id, upload) {
  const response = await httpClient.put(`${BASE}/documents/${segment(id)}`, form(upload), { timeout: 60_000 })
  return { document: response.data, changed: response.status === 202 }
}

/**
 * @param {string} id
 * @returns {Promise<KnowledgeDocument>}
 */
export async function reindexKnowledgeDocument(id) {
  return (await httpClient.post(`${BASE}/documents/${segment(id)}/reindex`)).data
}

/**
 * @param {string} id
 * @returns {Promise<void>}
 */
export async function deleteKnowledgeDocument(id) {
  await httpClient.delete(`${BASE}/documents/${segment(id)}`)
}

/**
 * @param {KnowledgeSearchRequest} request
 * @returns {Promise<KnowledgeSearchResult>}
 */
export async function searchKnowledge(request) {
  return (await httpClient.post(`${BASE}/search`, request)).data
}

export const knowledgeKeys = {
  all: ['knowledge'],
  lists: () => [...knowledgeKeys.all, 'documents'],
  /** @param {KnowledgeDocumentParams} params */
  list: (params) => [...knowledgeKeys.lists(), params],
}

/**
 * A document still waiting or being indexed: the list refreshes itself until it is done.
 * @param {KnowledgeDocument} document
 * @returns {boolean}
 */
export function isIndexing(document) {
  return document.status === 'UPLOADED' || document.status === 'PROCESSING'
}

/** @param {KnowledgeDocumentParams} params */
export function useKnowledgeDocuments(params) {
  return useQuery({
    queryKey: knowledgeKeys.list(params),
    queryFn: () => listKnowledgeDocuments(params),
    placeholderData: keepPreviousData,
    refetchInterval: (query) => (query.state.data?.content.some(isIndexing) ? 4000 : false),
  })
}

/**
 * @template TArgs
 * @template TResult
 * @param {(args: TArgs) => Promise<TResult>} run
 */
function useKnowledgeMutation(run) {
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
  return useKnowledgeMutation(({ id, upload }) => replaceKnowledgeDocument(id, upload))
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
