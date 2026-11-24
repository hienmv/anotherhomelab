import { useEffect, useState } from 'react'
import { onConnectionChange } from '../../api'
import { Spinner } from '../atoms/Spinner'

/** Shown while the app retries a brief outage by itself (Postgres failover, restart). */
export function ConnectionBanner() {
  const [reconnecting, setReconnecting] = useState(false)
  useEffect(() => onConnectionChange(setReconnecting), [])
  if (!reconnecting) return null
  return (
    <div className="status status--pending" role="status">
      <Spinner /> Reconnecting… TicketLite is briefly unavailable (usually under a minute). This page retries by
      itself.
    </div>
  )
}
