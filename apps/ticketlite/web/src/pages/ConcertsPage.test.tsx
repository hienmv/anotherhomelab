import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Concert, User } from '../types'
import { ConcertsPage } from './ConcertsPage'

const mocks = vi.hoisted(() => ({
  user: null as User | null,
  concerts: vi.fn(),
  buy: vi.fn(),
  order: vi.fn(),
}))

vi.mock('../auth', () => ({
  useAuth: () => ({ user: mocks.user, loading: false, login: vi.fn(), logout: vi.fn() }),
}))

vi.mock('../api', async (importOriginal) => {
  const real = await importOriginal<typeof import('../api')>()
  return { ...real, api: { ...real.api, concerts: mocks.concerts, buy: mocks.buy, order: mocks.order } }
})

const concerts: Concert[] = [
  { id: 1, title: 'Hot Show', artist: 'A', venue: 'V', startsAt: '2026-12-20T09:00:00Z', priceCents: 100, totalTickets: 10, available: 5, hot: true, maxPerUser: 2 },
  { id: 2, title: 'Calm Show', artist: 'B', venue: 'V', startsAt: '2027-01-08T11:00:00Z', priceCents: 100, totalTickets: 10, available: 10, hot: false, maxPerUser: 6 },
]

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<ConcertsPage />} />
        <Route path="/login" element={<p>login page</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ConcertsPage', () => {
  beforeEach(() => {
    mocks.user = null
    mocks.concerts.mockResolvedValue(concerts)
    mocks.buy.mockReset()
    mocks.order.mockReset()
  })

  it('separates hot tickets from the rest', async () => {
    renderPage()
    const hot = await screen.findByRole('heading', { name: '🔥 Hot tickets' })
    expect(hot.parentElement).toHaveTextContent('Hot Show')
    expect(hot.parentElement).not.toHaveTextContent('Calm Show')
    expect(screen.getByRole('heading', { name: 'All concerts' }).parentElement).toHaveTextContent('Calm Show')
  })

  it('sends anonymous buyers to the login page', async () => {
    renderPage()
    await screen.findByText('Hot Show')
    await userEvent.click(screen.getAllByRole('button', { name: 'Buy' })[0])
    expect(await screen.findByText('login page')).toBeInTheDocument()
    expect(mocks.buy).not.toHaveBeenCalled()
  })

  it('shows confirmation once the worker confirms the order', async () => {
    mocks.user = { id: 1, username: 'alice', displayName: 'Alice' }
    mocks.buy.mockResolvedValue({ id: 'o1', concertId: 1, concertTitle: 'Hot Show', quantity: 1, status: 'CONFIRMED', createdAt: '' })
    renderPage()
    await screen.findByText('Hot Show')
    await userEvent.click(screen.getAllByRole('button', { name: 'Buy' })[0])
    expect(await screen.findByText(/Confirmed! 1 ticket\(s\) for Hot Show/)).toBeInTheDocument()
    expect(mocks.buy).toHaveBeenCalledWith(1, 1)
  })

  it('explains a failed order', async () => {
    mocks.user = { id: 1, username: 'alice', displayName: 'Alice' }
    mocks.buy.mockResolvedValue({ id: 'o2', concertId: 1, concertTitle: 'Hot Show', quantity: 1, status: 'FAILED', reason: 'SOLD_OUT', createdAt: '' })
    renderPage()
    await screen.findByText('Hot Show')
    await userEvent.click(screen.getAllByRole('button', { name: 'Buy' })[0])
    expect(await screen.findByText('Order failed: SOLD_OUT')).toBeInTheDocument()
  })
})
