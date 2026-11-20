import { Badge } from '../../shared/components/ui'
import type { StockView } from './api'

/** Out of stock (red), low stock (amber) or in stock (green), from the flags the server computed. */
export function StockStatusBadge({ stock }: { stock: Pick<StockView, 'inStock' | 'lowStock'> }) {
  if (!stock.inStock) {
    return (
      <Badge tone="red" dot>
        Out of stock
      </Badge>
    )
  }
  if (stock.lowStock) {
    return (
      <Badge tone="amber" dot>
        Low stock
      </Badge>
    )
  }
  return (
    <Badge tone="green" dot>
      In stock
    </Badge>
  )
}
