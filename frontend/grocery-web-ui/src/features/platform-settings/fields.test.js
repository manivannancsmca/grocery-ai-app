import { describe, expect, it } from 'vitest'
import { ApiError } from '../../shared/api/problemDetail'
import {
  buildUpdate,
  draftFrom,
  fieldGroups,
  fieldState,
  formatSetting,
  optimisticView,
  parseSetting,
  SETTINGS_AREA_DEFINITIONS,
  serverFieldErrors,
} from './fields'

function definition(area) {
  const found = SETTINGS_AREA_DEFINITIONS.find((item) => item.area === area)
  if (!found) throw new Error(`no area ${area}`)
  return found
}

function field(area, key) {
  const found = definition(area).fields.find((item) => item.key === key)
  if (!found) throw new Error(`no field ${key}`)
  return found
}

const deliveryView = {
  area: 'delivery',
  values: { searchRadiiKm: [3, 6, 10], offersPerWave: 3, offerTimeLimitSeconds: 30, locationFreshnessSeconds: 60 },
  defaults: { searchRadiiKm: [3, 6, 10], offersPerWave: 2, offerTimeLimitSeconds: 30, locationFreshnessSeconds: 60 },
  overridden: ['offersPerWave'],
  updatedAt: '2026-11-17T05:12:00Z',
  updatedBy: 'Dev Ops',
  version: 4,
}

describe('platform settings fields', () => {
  it('covers the six areas in display order, with the delivery fees grouped by store type', () => {
    expect(SETTINGS_AREA_DEFINITIONS.map((item) => item.area)).toEqual([
      'delivery',
      'delivery-fees',
      'grocery-stores',
      'restaurants',
      'returns',
      'reviews',
    ])
    expect(fieldGroups(definition('delivery-fees')).map((group) => [group.title, group.fields.length])).toEqual([
      ['Grocery stores', 5],
      ['Restaurants', 3],
    ])
    expect(fieldGroups(definition('returns'))).toHaveLength(1)
  })

  it('formats values with their unit (fractional defaults as they are)', () => {
    expect(formatSetting(field('returns', 'returnWindowHours'), 48)).toBe('48 h')
    expect(formatSetting(field('delivery', 'searchRadiiKm'), [3, 6, 10])).toBe('3, 6, 10 km')
    expect(formatSetting(field('delivery', 'offersPerWave'), 1)).toBe('1 partner')
    expect(formatSetting(field('reviews', 'reviewWindowDays'), 30)).toBe('30 days')
    expect(formatSetting(field('grocery-stores', 'acceptanceTimeMinutes'), 0.5)).toBe('0.5 min')
    expect(formatSetting(field('delivery-fees', 'groceryFreeAbove'), 499)).toBe('₹499')
    expect(formatSetting(field('delivery-fees', 'groceryBaseFee'), 25.5)).toBe('₹25.50')
    expect(formatSetting(field('delivery-fees', 'groceryIncludedKm'), 2.5)).toBe('2.5 km')
  })

  it('validates with the services’ bounds', () => {
    const wave = field('delivery', 'offersPerWave')
    expect(parseSetting(wave, ' 3 ')).toEqual({ ok: true, value: 3 })
    expect(parseSetting(wave, '11')).toEqual({ ok: false, error: 'Must be between 1 and 10' })
    expect(parseSetting(wave, '2.5')).toEqual({ ok: false, error: 'Enter a whole number' })
    expect(parseSetting(wave, '')).toEqual({ ok: false, error: 'Enter a value, or reset it to the default' })

    const radii = field('delivery', 'searchRadiiKm')
    expect(parseSetting(radii, '2.5, 5,10')).toEqual({ ok: true, value: [2.5, 5, 10] })
    expect(parseSetting(radii, '6, 3')).toEqual({ ok: false, error: 'Each radius must be larger than the one before' })
    expect(parseSetting(radii, '3, 3')).toEqual({ ok: false, error: 'Each radius must be larger than the one before' })
    expect(parseSetting(radii, '0.4')).toEqual({ ok: false, error: 'Each radius must be between 0.5 and 50 km' })
    expect(parseSetting(radii, '1, 2, 3, 4, 5, 6')).toEqual({ ok: false, error: 'Use at most 5 steps' })
    expect(parseSetting(radii, '3, six')).toMatchObject({ ok: false })
    expect(parseSetting(radii, '1.25')).toMatchObject({ ok: false })

    const fee = field('delivery-fees', 'groceryBaseFee')
    expect(parseSetting(fee, '0')).toEqual({ ok: true, value: 0 })
    expect(parseSetting(fee, '30.75')).toEqual({ ok: true, value: 30.75 })
    expect(parseSetting(fee, '30.755')).toEqual({
      ok: false,
      error: 'Enter an amount in rupees, with at most two decimals',
    })
    expect(parseSetting(fee, '501')).toEqual({ ok: false, error: 'Must be between ₹0 and ₹500' })
    expect(parseSetting(field('delivery-fees', 'groceryIncludedKm'), '3.25')).toMatchObject({ ok: false })
  })

  it('sends overrides as values, everything else as null (default), with the version the edit started from', () => {
    const area = definition('delivery')
    const draft = draftFrom(area, deliveryView)
    expect(draft.version).toBe(4)
    expect(buildUpdate(area, draft, deliveryView)).toEqual({
      values: { searchRadiiKm: null, offersPerWave: 3, offerTimeLimitSeconds: null, locationFreshnessSeconds: null },
      expectedVersion: 4,
    })

    const edited = {
      ...draft,
      fields: {
        ...draft.fields,
        offerTimeLimitSeconds: { text: '45', useDefault: false },
        offersPerWave: { text: '2', useDefault: true },
      },
    }
    expect(buildUpdate(area, edited, deliveryView).values).toEqual({
      searchRadiiKm: null,
      offersPerWave: null,
      offerTimeLimitSeconds: 45,
      locationFreshnessSeconds: null,
    })
    expect(fieldState(field('delivery', 'offersPerWave'), edited.fields.offersPerWave, deliveryView)).toMatchObject({
      value: null,
      changed: false,
      unsaved: true,
    })
    expect(
      fieldState(field('delivery', 'offerTimeLimitSeconds'), edited.fields.offerTimeLimitSeconds, deliveryView),
    ).toMatchObject({ value: 45, changed: true, unsaved: true })
  })

  it('keeps following the default when the default is typed again into a field without an override', () => {
    const wave = field('delivery', 'offerTimeLimitSeconds')
    expect(fieldState(wave, { text: '30', useDefault: false }, deliveryView)).toEqual({
      value: null,
      error: null,
      changed: false,
      unsaved: false,
    })
    expect(fieldState(wave, { text: '9', useDefault: false }, deliveryView)).toEqual({
      value: null,
      error: 'Must be between 10 and 300',
      changed: true,
      unsaved: true,
    })
  })

  it('builds the optimistic view: new values, defaults where reset', () => {
    const view = optimisticView(deliveryView, {
      values: {
        searchRadiiKm: [2, 4],
        offersPerWave: null,
        offerTimeLimitSeconds: null,
        locationFreshnessSeconds: null,
      },
      expectedVersion: 4,
    })
    expect(view.values).toEqual({
      searchRadiiKm: [2, 4],
      offersPerWave: 2,
      offerTimeLimitSeconds: 30,
      locationFreshnessSeconds: 60,
    })
    expect(view.overridden).toEqual(['searchRadiiKm'])
    expect(view.version).toBe(4)
  })

  it('maps the server’s field errors to the card’s settings', () => {
    const error = new ApiError('Request validation failed.', {
      kind: 'http',
      status: 400,
      correlationId: null,
      problem: {
        errorCode: 'VALIDATION_FAILED',
        errors: [
          { field: 'values.searchRadiiKm[1]', message: 'must be between 0.5 and 50' },
          { field: 'values.offersPerWave', message: 'must be between 1 and 10' },
          { field: 'expectedVersion', message: 'must not be null' },
        ],
      },
    })
    expect(serverFieldErrors(error, definition('delivery'))).toEqual({
      byKey: { searchRadiiKm: 'must be between 0.5 and 50', offersPerWave: 'must be between 1 and 10' },
      other: ['expectedVersion: must not be null'],
    })
  })
})
