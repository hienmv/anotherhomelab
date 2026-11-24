import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, errorMessage, isUnavailable, onConnectionChange, READ_RETRY_FOR_MS } from './api'

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

describe('brief outages (design/2026-11-24-graceful-db-outage.md)', () => {
  const json = (status: number, body: object, headers: Record<string, string> = {}) =>
    new Response(JSON.stringify(body), { status, headers })

  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('retries a read after 503, honouring Retry-After, and shows "reconnecting" meanwhile', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(503, { error: 'UNAVAILABLE' }, { 'Retry-After': '5' }))
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(json(200, []))
    vi.stubGlobal('fetch', fetchMock)
    const states: boolean[] = []
    const off = onConnectionChange((r) => states.push(r))

    const result = api.concerts()
    await vi.advanceTimersByTimeAsync(4_999)
    expect(fetchMock).toHaveBeenCalledTimes(1) // waits the 5 s the server asked for
    await vi.advanceTimersByTimeAsync(1 + 2_000) // then the network error: 2 s backoff
    await expect(result).resolves.toEqual([])
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(states).toEqual([false, true, false])
    off()
  })

  it('gives up on a read after READ_RETRY_FOR_MS with UNAVAILABLE', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => json(503, { error: 'UNAVAILABLE' }, { 'Retry-After': '5' })))
    const result = api.concerts().catch((e) => e)
    await vi.advanceTimersByTimeAsync(READ_RETRY_FOR_MS + 10_000)
    const e = await result
    expect(e).toBeInstanceOf(ApiError)
    expect(e).toMatchObject({ status: 503, code: 'UNAVAILABLE' })
    expect(isUnavailable(e)).toBe(true)
    expect(errorMessage(e)).toMatch(/briefly unavailable/)
  })

  it('never retries a write (it could order twice)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(503, { error: 'UNAVAILABLE' }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(api.buy(1, 1)).rejects.toMatchObject({ status: 503, code: 'UNAVAILABLE' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('does not retry real errors', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(500, { error: 'INTERNAL' }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(api.concerts()).rejects.toMatchObject({ status: 500 })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('treats a proxy 502 without JSON as UNAVAILABLE', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Bad Gateway', { status: 502 })))
    await expect(api.buy(1, 1)).rejects.toMatchObject({ status: 502, code: 'UNAVAILABLE' })
  })
})
