import type { Order } from '../../types'
import { Badge } from '../atoms/Badge'

const tone = { PENDING: 'info', CONFIRMED: 'success', FAILED: 'danger' } as const

export function OrderTable({ orders }: { orders: Order[] }) {
  if (orders.length === 0) return <p className="muted">No orders yet.</p>
  return (
    <table className="table">
      <thead>
        <tr>
          <th>Concert</th>
          <th>Tickets</th>
          <th>Status</th>
          <th>Ordered</th>
        </tr>
      </thead>
      <tbody>
        {orders.map((o) => (
          <tr key={o.id}>
            <td>{o.concertTitle}</td>
            <td>{o.quantity}</td>
            <td>
              <Badge tone={tone[o.status]}>{o.status}</Badge> {o.reason && <small className="muted">{o.reason}</small>}
            </td>
            <td>{new Date(o.createdAt).toLocaleString()}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
