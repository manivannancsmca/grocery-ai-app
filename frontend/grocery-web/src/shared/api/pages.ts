import type { PageResponse } from './types'

/**
 * Reads a list endpoint that answers either a `PageResponse` or a plain array (Phase 12 lists whose contract
 * does not fix the shape): an array becomes a single page holding everything.
 */
export function toPage<T>(data: PageResponse<T> | readonly T[] | null | undefined): PageResponse<T> {
  if (Array.isArray(data)) {
    const content = [...(data as readonly T[])]
    return { content, page: 0, size: Math.max(content.length, 1), totalElements: content.length, totalPages: content.length > 0 ? 1 : 0 }
  }
  const page = data as PageResponse<T> | null | undefined
  if (!page || !Array.isArray(page.content)) return { content: [], page: 0, size: 1, totalElements: 0, totalPages: 0 }
  return {
    content: page.content,
    page: page.page ?? 0,
    size: page.size ?? page.content.length,
    totalElements: page.totalElements ?? page.content.length,
    totalPages: page.totalPages ?? (page.content.length > 0 ? 1 : 0),
  }
}
