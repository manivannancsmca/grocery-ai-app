import { describe, expect, it } from 'vitest'
import { formatCountdown, formatDistanceKm, formatMoney, formatPhone, formatPrice, formatRelativeTime, formatSignedPercent } from './format'

describe('formatPrice', () => {
  it('formats rupees with Indian digit grouping and paise only when needed', () => {
    expect(formatPrice(249)).toBe('₹249')
    expect(formatPrice(249.5)).toBe('₹249.50')
    expect(formatPrice(125000)).toBe('₹1,25,000')
    expect(formatPrice(null)).toBe('—')
  })
})

describe('formatMoney', () => {
  it('always shows paise, with Indian digit grouping', () => {
    expect(formatMoney(49)).toBe('₹49.00')
    expect(formatMoney(49.5)).toBe('₹49.50')
    expect(formatMoney(125000)).toBe('₹1,25,000.00')
    expect(formatMoney(undefined)).toBe('—')
  })
})

describe('formatSignedPercent', () => {
  it('marks the direction of a change', () => {
    expect(formatSignedPercent(-48.98)).toBe('−48.98%')
    expect(formatSignedPercent(12.5)).toBe('+12.5%')
    expect(formatSignedPercent(0)).toBe('0%')
    expect(formatSignedPercent(null)).toBe('—')
  })
})

describe('formatCountdown', () => {
  it('shows minutes and seconds left, rounding up and never below zero', () => {
    expect(formatCountdown(754_000)).toBe('12:34')
    expect(formatCountdown(15 * 60_000)).toBe('15:00')
    expect(formatCountdown(500)).toBe('0:01')
    expect(formatCountdown(-3_000)).toBe('0:00')
  })
})

describe('formatPhone', () => {
  it('groups an Indian mobile number as people read it and leaves anything else alone', () => {
    expect(formatPhone('+919840066004')).toBe('+91 98400 66004')
    expect(formatPhone('9840066004')).toBe('9840066004')
    expect(formatPhone('+14155550100')).toBe('+14155550100')
  })
})

describe('formatRelativeTime', () => {
  it('reads like a feed and falls back to the date after a week', () => {
    const now = Date.parse('2026-11-11T12:00:00Z')
    expect(formatRelativeTime('2026-11-11T11:59:30Z', now)).toBe('Just now')
    expect(formatRelativeTime('2026-11-11T12:00:05Z', now)).toBe('Just now')
    expect(formatRelativeTime('2026-11-11T11:55:00Z', now)).toBe('5 min ago')
    expect(formatRelativeTime('2026-11-11T09:00:00Z', now)).toBe('3 h ago')
    expect(formatRelativeTime('2026-11-10T11:00:00Z', now)).toBe('Yesterday')
    expect(formatRelativeTime('2026-11-07T12:00:00Z', now)).toBe('4 days ago')
    expect(formatRelativeTime('2026-10-01T12:00:00Z', now)).toBe('1 Oct 2026')
    expect(formatRelativeTime(null, now)).toBe('—')
  })
})

describe('formatDistanceKm', () => {
  it('uses metres below a kilometre and one decimal below ten', () => {
    expect(formatDistanceKm(0.35)).toBe('350 m')
    expect(formatDistanceKm(2.437)).toBe('2.4 km')
    expect(formatDistanceKm(12.6)).toBe('13 km')
    expect(formatDistanceKm(null)).toBeNull()
  })
})
