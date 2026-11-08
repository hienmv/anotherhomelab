import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../api'
import { useAuth } from '../auth'
import { Spinner } from '../components/atoms/Spinner'
import { StatusMessage, type Status } from '../components/molecules/StatusMessage'
import { ConcertGrid } from '../components/organisms/ConcertGrid'
import { PageLayout } from '../components/templates/PageLayout'
import type { Concert, Order } from '../types'

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Orders are confirmed asynchronously by a worker: poll until it is no longer PENDING. */
async function waitForOrder(order: Order): Promise<Order> {
  let current = order
  for (let i = 0; i < 30 && current.status === 'PENDING'; i++) {
    await sleep(500)
    current = await api.order(order.id)
  }
  return current
}

export function ConcertsPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [concerts, setConcerts] = useState<Concert[] | null>(null)
  const [status, setStatus] = useState<Status | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)

  const load = useCallback(() => {
    api.concerts().then(setConcerts).catch((e) => setStatus({ kind: 'error', text: errorMessage(e) }))
  }, [])

  useEffect(load, [load])

  async function buy(concert: Concert, quantity: number) {
    if (!user) {
      navigate('/login')
      return
    }
    setBusyId(concert.id)
    setStatus({ kind: 'pending', text: `Reserving ${quantity} ticket(s) for ${concert.title}…` })
    try {
      const result = await waitForOrder(await api.buy(concert.id, quantity))
      if (result.status === 'CONFIRMED') {
        setStatus({ kind: 'success', text: `Confirmed! ${quantity} ticket(s) for ${concert.title}.` })
      } else if (result.status === 'FAILED') {
        setStatus({ kind: 'error', text: `Order failed: ${result.reason ?? 'unknown reason'}` })
      } else {
        setStatus({ kind: 'info', text: 'Still processing. Check "My orders" shortly.' })
      }
    } catch (e) {
      setStatus({ kind: 'error', text: errorMessage(e) })
    } finally {
      setBusyId(null)
      load()
    }
  }

  return (
    <PageLayout>
      <StatusMessage status={status} />
      {concerts === null ? (
        <Spinner />
      ) : (
        <>
          <ConcertGrid title="🔥 Hot tickets" concerts={concerts.filter((c) => c.hot)} busyId={busyId} onBuy={buy} />
          <ConcertGrid title="All concerts" concerts={concerts.filter((c) => !c.hot)} busyId={busyId} onBuy={buy} />
        </>
      )}
    </PageLayout>
  )
}
