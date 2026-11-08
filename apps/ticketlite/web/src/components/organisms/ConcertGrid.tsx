import type { Concert } from '../../types'
import { ConcertCard } from './ConcertCard'

interface Props {
  title: string
  concerts: Concert[]
  busyId: number | null
  onBuy: (concert: Concert, quantity: number) => void
}

export function ConcertGrid({ title, concerts, busyId, onBuy }: Props) {
  if (concerts.length === 0) return null
  return (
    <section className="section">
      <h2>{title}</h2>
      <div className="grid">
        {concerts.map((c) => (
          <ConcertCard key={c.id} concert={c} busy={busyId === c.id} onBuy={onBuy} />
        ))}
      </div>
    </section>
  )
}
