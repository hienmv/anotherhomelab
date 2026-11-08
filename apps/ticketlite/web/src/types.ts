export interface User {
  id: number
  username: string
  displayName: string
}

export interface Concert {
  id: number
  title: string
  artist: string
  venue: string
  startsAt: string
  priceCents: number
  totalTickets: number
  available: number
  hot: boolean
  maxPerUser: number
}

export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'FAILED'

export interface Order {
  id: string
  concertId: number
  concertTitle: string
  quantity: number
  status: OrderStatus
  reason?: string | null
  createdAt: string
}
