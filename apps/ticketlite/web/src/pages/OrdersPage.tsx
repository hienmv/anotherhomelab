import { useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { api, errorMessage } from '../api'
import { useAuth } from '../auth'
import { Spinner } from '../components/atoms/Spinner'
import { StatusMessage } from '../components/molecules/StatusMessage'
import { OrderTable } from '../components/organisms/OrderTable'
import { PageLayout } from '../components/templates/PageLayout'
import type { Order } from '../types'

export function OrdersPage() {
  const { user, loading } = useAuth()
  const [orders, setOrders] = useState<Order[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (user) api.orders().then(setOrders).catch((e) => setError(errorMessage(e)))
  }, [user])

  if (!loading && !user) return <Navigate to="/login" replace />
  return (
    <PageLayout>
      <h2>My orders</h2>
      <StatusMessage status={error ? { kind: 'error', text: error } : null} />
      {orders === null ? <Spinner /> : <OrderTable orders={orders} />}
    </PageLayout>
  )
}
