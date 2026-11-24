import type { Concert, Order, User } from './types'

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    public detail?: string,
  ) {
    super(code)
  }
}

/**
 * Brief outages (Postgres failover or restart, Redis restart: under ~1.5 min) are answered 503 +
 * Retry-After by the api, 502/504 by the proxy, or fail as network errors. Reads (GET) are retried by
 * themselves for up to READ_RETRY_FOR_MS while a banner says "reconnecting"; writes (POST) are NOT
 * retried: the first attempt may have worked, and a blind retry could order the tickets twice.
 * docs/design/2026-11-24-graceful-db-outage.md
 */
export const READ_RETRY_FOR_MS = 90_000
const RETRYABLE_STATUS = new Set([502, 503, 504])
const MAX_DELAY_MS = 10_000

type ConnectionListener = (reconnecting: boolean) => void
const listeners = new Set<ConnectionListener>()
let reconnecting = false

function setReconnecting(value: boolean) {
  if (value === reconnecting) return
  reconnecting = value
  listeners.forEach((fn) => fn(value))
}

/** Subscribe to "the app is retrying a brief outage" (true) / "back to normal" (false). */
export function onConnectionChange(fn: ConnectionListener): () => void {
  listeners.add(fn)
  fn(reconnecting)
  return () => {
    listeners.delete(fn)
  }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Wait before the next try: the server's Retry-After if it sent one, else 1 s, 2 s, 4 s, ... (max 10 s). */
function retryDelay(res: Response | null, attempt: number): number {
  const header = Number(res?.headers.get('Retry-After'))
  const base = header > 0 ? header * 1000 : 1000 * 2 ** attempt
  return Math.min(base, MAX_DELAY_MS)
}

async function toApiError(res: Response): Promise<ApiError> {
  let code = RETRYABLE_STATUS.has(res.status) ? 'UNAVAILABLE' : 'ERROR'
  let detail: string | undefined
  try {
    const body = await res.json()
    code = body.error ?? code
    detail = body.message
  } catch {
    /* non-JSON error body (e.g. the proxy's own 502 page) */
  }
  return new ApiError(res.status, code, detail)
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const isRead = (init?.method ?? 'GET').toUpperCase() === 'GET'
  const started = Date.now()
  for (let attempt = 0; ; attempt++) {
    let res: Response | null = null
    let failure: unknown
    try {
      res = await fetch(`/api${path}`, {
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        ...init,
      })
    } catch (e) {
      failure = e // network error: the request did not get an answer
    }
    const retryable = res === null || RETRYABLE_STATUS.has(res.status)
    if (isRead && retryable && Date.now() - started < READ_RETRY_FOR_MS) {
      setReconnecting(true)
      await sleep(retryDelay(res, attempt))
      continue
    }
    // a read is done (answered, or gave up after READ_RETRY_FOR_MS), or a write was answered fine
    if (isRead || res?.ok) setReconnecting(false)
    if (res === null) throw failure
    if (!res.ok) throw await toApiError(res)
    return (res.status === 204 ? undefined : await res.json()) as T
  }
}

export const api = {
  me: () => request<User>('/auth/me'),
  login: (username: string, password: string) =>
    request<User>('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  logout: () => request<void>('/auth/logout', { method: 'POST' }),
  concerts: () => request<Concert[]>('/concerts'),
  buy: (concertId: number, quantity: number) =>
    request<Order>('/orders', { method: 'POST', body: JSON.stringify({ concertId, quantity }) }),
  order: (id: string) => request<Order>(`/orders/${id}`),
  orders: () => request<Order[]>('/orders'),
}

const MESSAGES: Record<string, string> = {
  SOLD_OUT: 'Sorry, this concert is sold out.',
  LIMIT: 'You reached the ticket limit for this concert.',
  INVALID_CREDENTIALS: 'Wrong username or password.',
  UNAUTHORIZED: 'Please log in first.',
  INVALID_QUANTITY: 'Invalid number of tickets.',
  UNAVAILABLE: 'TicketLite is briefly unavailable (usually under a minute). Please try again in a few seconds.',
}

/** A brief outage (not a bug, not a wrong input): worth trying again in a few seconds. */
export function isUnavailable(e: unknown): boolean {
  return e instanceof ApiError && RETRYABLE_STATUS.has(e.status)
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return MESSAGES[e.code] ?? `Something went wrong (${e.code}).`
  return 'Network error. Please try again.'
}
