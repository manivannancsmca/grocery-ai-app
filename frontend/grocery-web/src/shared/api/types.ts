/** Paged list returned by every admin list endpoint (contract §1). */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export const DEFAULT_PAGE_SIZE = 20

/** Drops empty values so they are not sent as `?status=&query=`. */
export function cleanParams<T extends object>(params: T): Partial<T> {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== ''),
  ) as Partial<T>
}

/** Encodes one path segment (IDs are UUIDs, but never trust a value pasted into a URL). */
export function segment(value: string): string {
  return encodeURIComponent(value)
}
