import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, errorMessage } from './api'

afterEach(() => vi.unstubAllGlobals())

describe('api client', () => {
  it('turns an error body into an ApiError with its code', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'SOLD_OUT' }), { status: 409 })))
    await expect(api.buy(1, 1)).rejects.toMatchObject({ status: 409, code: 'SOLD_OUT' })
  })

  it('sends JSON and same-origin credentials', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 'x', status: 'PENDING' }), { status: 202 }))
    vi.stubGlobal('fetch', fetchMock)
    await api.buy(7, 2)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/orders')
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('same-origin')
    expect(JSON.parse(init.body)).toEqual({ concertId: 7, quantity: 2 })
  })

  it('handles 204 No Content', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
    await expect(api.logout()).resolves.toBeUndefined()
  })
})

describe('errorMessage', () => {
  it('maps known codes to friendly text', () => {
    expect(errorMessage(new ApiError(409, 'SOLD_OUT'))).toBe('Sorry, this concert is sold out.')
    expect(errorMessage(new ApiError(409, 'LIMIT'))).toMatch(/ticket limit/)
  })

  it('falls back for unknown codes and network errors', () => {
    expect(errorMessage(new ApiError(500, 'INTERNAL'))).toMatch(/INTERNAL/)
    expect(errorMessage(new TypeError('Failed to fetch'))).toMatch(/Network error/)
  })
})
