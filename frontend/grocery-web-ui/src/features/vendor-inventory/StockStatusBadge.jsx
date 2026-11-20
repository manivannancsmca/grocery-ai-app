import { Badge } from '../../shared/components/ui'

/** @typedef {import('./api').StockView} StockView */

/**
 * Out of stock (red), low stock (amber) or in stock (green), from the flags the server computed.
 * @param {{ stock: Pick<StockView, 'inStock' | 'lowStock'> }} props
 */
export function StockStatusBadge({ stock }) {
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
