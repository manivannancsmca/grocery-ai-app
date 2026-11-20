import { describe, expect, it } from 'vitest'
import type { SettingChange } from './api'
import { type AreaDefinition, SETTINGS_AREA_DEFINITIONS } from './fields'
import { changeLine, groupBySave } from './history'

function definition(area: string): AreaDefinition {
  const found = SETTINGS_AREA_DEFINITIONS.find((item) => item.area === area)
  if (!found) throw new Error(`no area ${area}`)
  return found
}

function change(field: string, oldValue: SettingChange['oldValue'], newValue: SettingChange['newValue'], changedAt: string, by = 'admin-1', name: string | null = 'Dev Ops'): SettingChange {
  return { field, oldValue, newValue, changedAt, changedBy: { id: by, name } }
}

describe('platform settings history', () => {
  it('shows a change with the field’s label and unit, and null as the default', () => {
    expect(changeLine(definition('delivery'), change('offersPerWave', 2, 1, '2026-11-17T05:12:00Z'))).toEqual({
      field: 'offersPerWave',
      label: 'Partners offered per wave',
      from: '2 partners',
      to: '1 partner',
    })
    expect(changeLine(definition('delivery'), change('searchRadiiKm', null, [1.5, 4], 't'))).toMatchObject({ from: null, to: '1.5, 4 km' })
    expect(changeLine(definition('returns'), change('returnWindowHours', 72, null, 't'))).toMatchObject({
      label: 'Return window after delivery',
      from: '72 h',
      to: null,
    })
    // Fees name their group and show rupees.
    expect(changeLine(definition('delivery-fees'), change('groceryBaseFee', 25, 40.5, 't'))).toEqual({
      field: 'groceryBaseFee',
      label: 'Grocery base fee',
      from: '₹25',
      to: '₹40.50',
    })
  })

  it('shows what the page does not know as it came: an unknown field, a value stored by hand', () => {
    expect(changeLine(definition('reviews'), change('editWindowDays', 1, [2, 3], 't'))).toEqual({
      field: 'editWindowDays',
      label: 'editWindowDays',
      from: '1',
      to: '2, 3',
    })
    expect(changeLine(definition('reviews'), change('reviewWindowDays', 'thirty', 14, 't'))).toMatchObject({ from: 'thirty', to: '14 days' })
  })

  it('groups the rows of one save (same time, same admin) in the card’s field order, newest save first', () => {
    const saves = groupBySave(definition('delivery'), [
      // the service sends one save's rows in the reverse order of writing
      change('offerTimeLimitSeconds', null, 45, '2026-11-17T05:12:00Z'),
      change('offersPerWave', 2, 3, '2026-11-17T05:12:00Z'),
      change('offersPerWave', null, 2, '2026-11-17T05:12:00Z', 'admin-2', '  '),
      change('searchRadiiKm', null, [2, 4, 8], '2026-11-16T09:30:00Z'),
    ])
    expect(saves.map((save) => [save.by, save.changedAt, save.lines.map((line) => line.field)])).toEqual([
      ['Dev Ops', '2026-11-17T05:12:00Z', ['offersPerWave', 'offerTimeLimitSeconds']],
      ['An admin', '2026-11-17T05:12:00Z', ['offersPerWave']],
      ['Dev Ops', '2026-11-16T09:30:00Z', ['searchRadiiKm']],
    ])
    expect(new Set(saves.map((save) => save.key)).size).toBe(3)
    expect(groupBySave(definition('delivery'), [])).toEqual([])
  })
})
