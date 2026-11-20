import { describe, expect, it } from 'vitest'
import { trackingView } from '../../test/deliveryFixtures'
import { etaValueText, liveEtaMinutes } from './eta'

describe('liveEtaMinutes', () => {
  it('is the estimate while a partner has the delivery', () => {
    expect(liveEtaMinutes(trackingView({ status: 'ASSIGNED', etaMinutes: 12 }))).toBe(12)
    expect(liveEtaMinutes(trackingView({ status: 'PICKED_UP', etaMinutes: 3 }))).toBe(3)
  })

  it('is nothing before a partner accepts, once the delivery is over, or without a number', () => {
    expect(liveEtaMinutes(undefined)).toBeNull()
    expect(liveEtaMinutes(trackingView({ status: 'SEARCHING', partner: null, etaMinutes: 20 }))).toBeNull()
    expect(liveEtaMinutes(trackingView({ status: 'DELIVERED', etaMinutes: 0 }))).toBeNull()
    expect(liveEtaMinutes(trackingView({ status: 'FAILED', etaMinutes: 4 }))).toBeNull()
    expect(liveEtaMinutes(trackingView({ status: 'CANCELLED', etaMinutes: 4 }))).toBeNull()
    expect(liveEtaMinutes(trackingView({ status: 'PICKED_UP', etaMinutes: null }))).toBeNull()
  })
})

describe('etaValueText', () => {
  it('reads "a minute" at the end, minutes otherwise', () => {
    expect(etaValueText(0)).toBe('a minute')
    expect(etaValueText(1)).toBe('a minute')
    expect(etaValueText(7)).toBe('7 min')
  })
})
