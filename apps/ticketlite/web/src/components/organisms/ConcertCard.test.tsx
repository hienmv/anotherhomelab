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

  it('shows progress while buying', () => {
    render(<ConcertCard concert={concert()} busy={true} onBuy={() => {}} />)
    expect(screen.getByRole('button', { name: 'Buying…' })).toBeDisabled()
  })
})
