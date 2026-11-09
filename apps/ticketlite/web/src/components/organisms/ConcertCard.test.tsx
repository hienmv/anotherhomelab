import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Concert } from '../../types'
import { ConcertCard } from './ConcertCard'

const concert = (over: Partial<Concert> = {}): Concert => ({
  id: 1,
  title: 'Neon Skyline World Tour',
  artist: 'Aurora Vale',
  venue: 'Tokyo Dome',
  startsAt: '2026-12-20T09:00:00Z',
  priceCents: 1_200_000,
  totalTickets: 200,
  available: 100,
  hot: false,
  maxPerUser: 6,
  ...over,
})

describe('ConcertCard', () => {
  it('marks hot concerts', () => {
    render(<ConcertCard concert={concert({ hot: true, maxPerUser: 2 })} busy={false} onBuy={() => {}} />)
    expect(screen.getByText(/Hot/)).toBeInTheDocument()
    expect(screen.getByText('max 2 / person')).toBeInTheDocument()
  })

  it('disables buying when sold out', () => {
    render(<ConcertCard concert={concert({ available: 0 })} busy={false} onBuy={() => {}} />)
    expect(screen.getByText('Sold out')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Buy' })).toBeDisabled()
  })

  it('warns when few tickets are left', () => {
    render(<ConcertCard concert={concert({ available: 3 })} busy={false} onBuy={() => {}} />)
    expect(screen.getByText('Only 3 left')).toBeInTheDocument()
  })

  it('never lets you pick more than what is left', async () => {
    const onBuy = vi.fn()
    const c = concert({ available: 1, maxPerUser: 6 })
    render(<ConcertCard concert={c} busy={false} onBuy={onBuy} />)
    expect(screen.getByRole('button', { name: 'More' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Buy' }))
    expect(onBuy).toHaveBeenCalledWith(c, 1)
  })

  it('buys the chosen quantity', async () => {
    const onBuy = vi.fn()
    const c = concert()
    render(<ConcertCard concert={c} busy={false} onBuy={onBuy} />)
    await userEvent.click(screen.getByRole('button', { name: 'More' }))
    await userEvent.click(screen.getByRole('button', { name: 'Buy' }))
    expect(onBuy).toHaveBeenCalledWith(c, 2)
  })

  it('disables Buy and explains why when the user reached the limit', () => {
    render(<ConcertCard concert={concert({ hot: true, maxPerUser: 2, userHeld: 2 })} busy={false} onBuy={() => {}} />)
    const buy = screen.getByRole('button', { name: 'Buy' })
    expect(buy).toBeDisabled()
    expect(screen.getByText('Limit reached')).toBeInTheDocument()
    // the reason is linked to the button for screen readers
    expect(buy).toHaveAccessibleDescription('You already have 2 tickets: the maximum for this concert (2 per person).')
  })

  it('explains sold out next to the disabled button', () => {
    render(<ConcertCard concert={concert({ available: 0, userHeld: 0 })} busy={false} onBuy={() => {}} />)
    expect(screen.getByRole('button', { name: 'Buy' })).toHaveAccessibleDescription('Sold out. No tickets left for this concert.')
  })

  it('lets the user buy only the remaining allowance', async () => {
    const onBuy = vi.fn()
    const c = concert({ hot: true, maxPerUser: 2, userHeld: 1 })
    render(<ConcertCard concert={c} busy={false} onBuy={onBuy} />)
    expect(screen.getByText('You have 1; you can buy 1 more.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'More' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Buy' }))
    expect(onBuy).toHaveBeenCalledWith(c, 1)
  })

  it('shows progress while buying', () => {
    render(<ConcertCard concert={concert()} busy={true} onBuy={() => {}} />)
    expect(screen.getByRole('button', { name: 'Buying…' })).toBeDisabled()
  })
})
