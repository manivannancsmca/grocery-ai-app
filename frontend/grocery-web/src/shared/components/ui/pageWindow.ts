/** First, last and the pages around the current one (0-based), with `null` for a gap: 0 … 4 5 6 … 19. */
export function pageWindow(page: number, totalPages: number): Array<number | null> {
  if (totalPages <= 7) return Array.from({ length: totalPages }, (_, index) => index)
  const start = Math.max(1, Math.min(page - 1, totalPages - 4))
  const end = Math.min(totalPages - 2, Math.max(page + 1, 3))
  const middle = Array.from({ length: end - start + 1 }, (_, index) => start + index)
  return [0, ...(start > 1 ? [null] : []), ...middle, ...(end < totalPages - 2 ? [null] : []), totalPages - 1]
}
