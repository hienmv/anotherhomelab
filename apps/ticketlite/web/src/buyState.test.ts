import { describe, expect, it } from 'vitest'
import { buyState } from './buyState'
import type { Concert } from './types'

const c = (over: Partial<Concert> = {}): Concert => ({
  id: 1, title: 'T', artist: 'A', venue: 'V', startsAt: '2026-12-20T09:00:00Z', priceCents: 100,
  totalTickets: 100, available: 50, hot: true, maxPerUser: 2, ...over,
})

describe('buyState', () => {
  it('anonymous users can buy up to the per-person max', () => {
    expect(buyState(c())).toEqual({ canBuy: true, max: 2, note: null })
  })

  it('blocks when the user already holds the maximum', () => {
    const s = buyState(c({ userHeld: 2 }))
    expect(s.canBuy).toBe(false)
    if (!s.canBuy) {
      expect(s.reason).toBe('LIMIT_REACHED')
      expect(s.message).toBe('You already have 2 tickets: the maximum for this concert (2 per person).')
    }
  })

  it('caps the pick at what is still allowed and says so', () => {
    expect(buyState(c({ userHeld: 1 }))).toEqual({ canBuy: true, max: 1, note: 'You have 1; you can buy 1 more.' })
  })

  it('caps the pick at what is left in stock', () => {
    expect(buyState(c({ hot: false, maxPerUser: 6, available: 3, userHeld: 0 }))).toMatchObject({ canBuy: true, max: 3 })
  })

  it('blocks when sold out', () => {
    expect(buyState(c({ available: 0, userHeld: 0 }))).toMatchObject({ canBuy: false, reason: 'SOLD_OUT' })
  })

  it('prefers the limit message when both apply (the user already has their tickets)', () => {
    expect(buyState(c({ available: 0, userHeld: 2 }))).toMatchObject({ canBuy: false, reason: 'LIMIT_REACHED' })
  })
})
