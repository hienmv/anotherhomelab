import { useState } from 'react'
import type { Concert } from '../../types'
import { Badge } from '../atoms/Badge'
import { Button } from '../atoms/Button'
import { Price } from '../atoms/Price'
import { ConcertMeta } from '../molecules/ConcertMeta'
import { QuantityPicker } from '../molecules/QuantityPicker'
import { buyState } from '../../buyState'

interface Props {
  concert: Concert
  busy: boolean
  onBuy: (concert: Concert, quantity: number) => void
}

export function ConcertCard({ concert, busy, onBuy }: Props) {
  const [qty, setQty] = useState(1)
  const state = buyState(concert)
  const soldOut = concert.available <= 0
  const lowStock = !soldOut && concert.available <= 10
  const max = state.canBuy ? state.max : 1
  const chosen = Math.min(qty, max)
  const hintId = `buy-hint-${concert.id}`
  const hint = state.canBuy ? state.note : state.message

  return (
    <article className={`card ${concert.hot ? 'card--hot' : ''}`}>
      <div className="card__badges">
        {concert.hot && <Badge tone="hot">🔥 Hot</Badge>}
        {soldOut && <Badge tone="danger">Sold out</Badge>}
        {lowStock && <Badge tone="info">Only {concert.available} left</Badge>}
        {!state.canBuy && state.reason === 'LIMIT_REACHED' && <Badge tone="muted">Limit reached</Badge>}
      </div>
      <h3 className="card__title">{concert.title}</h3>
      <p className="card__artist">{concert.artist}</p>
      <ConcertMeta venue={concert.venue} startsAt={concert.startsAt} />
      <div className="card__footer">
        <Price cents={concert.priceCents} />
        <small className="muted">max {concert.maxPerUser} / person</small>
      </div>
      <div className="card__actions">
        <QuantityPicker value={chosen} max={max} onChange={setQty} disabled={!state.canBuy || busy} />
        <Button
          disabled={!state.canBuy || busy}
          aria-describedby={hint ? hintId : undefined}
          onClick={() => onBuy(concert, chosen)}
        >
          {busy ? 'Buying…' : 'Buy'}
        </Button>
      </div>
      {hint && (
        <p id={hintId} className={`card__hint ${state.canBuy ? '' : 'card__hint--blocked'}`}>
          {hint}
        </p>
      )}
    </article>
  )
}
