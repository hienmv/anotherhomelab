import { useState } from 'react'
import type { Concert } from '../../types'
import { Badge } from '../atoms/Badge'
import { Button } from '../atoms/Button'
import { Price } from '../atoms/Price'
import { ConcertMeta } from '../molecules/ConcertMeta'
import { QuantityPicker } from '../molecules/QuantityPicker'

interface Props {
  concert: Concert
  busy: boolean
  onBuy: (concert: Concert, quantity: number) => void
}

export function ConcertCard({ concert, busy, onBuy }: Props) {
  const [qty, setQty] = useState(1)
  const soldOut = concert.available <= 0
  const max = Math.max(1, Math.min(concert.maxPerUser, concert.available))
  const lowStock = !soldOut && concert.available <= 10

  return (
    <article className={`card ${concert.hot ? 'card--hot' : ''}`}>
      <div className="card__badges">
        {concert.hot && <Badge tone="hot">🔥 Hot</Badge>}
        {soldOut && <Badge tone="danger">Sold out</Badge>}
        {lowStock && <Badge tone="info">Only {concert.available} left</Badge>}
      </div>
      <h3 className="card__title">{concert.title}</h3>
      <p className="card__artist">{concert.artist}</p>
      <ConcertMeta venue={concert.venue} startsAt={concert.startsAt} />
      <div className="card__footer">
        <Price cents={concert.priceCents} />
        <small className="muted">max {concert.maxPerUser} / person</small>
      </div>
      <div className="card__actions">
        <QuantityPicker value={Math.min(qty, max)} max={max} onChange={setQty} disabled={soldOut || busy} />
        <Button disabled={soldOut || busy} onClick={() => onBuy(concert, Math.min(qty, max))}>
          {busy ? 'Buying…' : 'Buy'}
        </Button>
      </div>
    </article>
  )
}
