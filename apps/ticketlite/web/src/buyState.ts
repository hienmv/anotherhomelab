import type { Concert } from './types'

export type BuyState =
  | { canBuy: true; max: number; note: string | null }
  | { canBuy: false; reason: 'SOLD_OUT' | 'LIMIT_REACHED'; message: string }

/**
 * Decides whether the Buy button is enabled, how many tickets can be picked, and what to tell the
 * user. Anonymous users (userHeld absent) can click Buy and are sent to the login page.
 */
export function buyState(c: Concert): BuyState {
  const held = c.userHeld ?? 0
  const remaining = c.maxPerUser - held
  if (c.userHeld != null && remaining <= 0) {
    return {
      canBuy: false,
      reason: 'LIMIT_REACHED',
      message: `You already have ${held} ticket${held === 1 ? '' : 's'}: the maximum for this concert (${c.maxPerUser} per person).`,
    }
  }
  if (c.available <= 0) {
    return { canBuy: false, reason: 'SOLD_OUT', message: 'Sold out. No tickets left for this concert.' }
  }
  const max = Math.max(1, Math.min(remaining, c.available))
  const note = held > 0 ? `You have ${held}; you can buy ${remaining} more.` : null
  return { canBuy: true, max, note }
}
