import { describe, expect, it } from 'vitest'
import { categoryLabel } from './api'

describe('categoryLabel', () => {
  it('shows the full path root → leaf', () => {
    expect(
      categoryLabel({
        id: 'c',
        name: 'Fresh Vegetables',
        path: ['Fruits & Vegetables', 'Fresh Vegetables'],
        active: true,
      }),
    ).toBe('Fruits & Vegetables › Fresh Vegetables')
  })

  it('handles an archived product whose category was deleted (category: null)', () => {
    expect(categoryLabel(null)).toBe('Category removed')
  })
})
