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

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, {
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!res.ok) {
    let code = 'ERROR'
    let detail: string | undefined
    try {
      const body = await res.json()
      code = body.error ?? code
      detail = body.message
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(res.status, code, detail)
  }
  return (res.status === 204 ? undefined : await res.json()) as T
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
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return MESSAGES[e.code] ?? `Something went wrong (${e.code}).`
  return 'Network error. Please try again.'
}
