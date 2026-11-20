import { describe, expect, it } from 'vitest'
import type { MenuItemView } from './api'
import { dishesLabel, dishMatches, menuIconFor } from './menuView'

const dosa: MenuItemView = {
  id: 'i1',
  categoryId: 'mc1',
  name: 'Masala Dosa',
  description: 'Filled with potato masala',
  price: 95,
  foodType: 'VEG',
  imageUrl: null,
  available: false,
  sortOrder: 0,
  updatedAt: '2026-11-11T10:00:00Z',
}

describe('menu view helpers', () => {
  it('matches dishes by name or description and by availability', () => {
    expect(dishMatches(dosa, '  DOSA ', 'ALL')).toBe(true)
    expect(dishMatches(dosa, 'potato', 'SOLD_OUT')).toBe(true)
    expect(dishMatches(dosa, '', 'AVAILABLE')).toBe(false)
    expect(dishMatches(dosa, 'idli', 'ALL')).toBe(false)
  })

  it('picks a picture from the name, the more specific word first', () => {
    expect(menuIconFor('Masala Dosa')).toBe('cooking')
    expect(menuIconFor('Egg Kothu Parotta')).toBe('egg')
    expect(menuIconFor('Drinks & Sweets')).toBe('juice')
    expect(menuIconFor('Chicken Biryani')).toBe('thali')
    expect(menuIconFor('Steamed Momos')).toBe('cutlery')
  })

  it('counts dishes in words', () => {
    expect(dishesLabel(1)).toBe('1 dish')
    expect(dishesLabel(4)).toBe('4 dishes')
  })
})
