import type { ReactNode } from 'react'
import { ConnectionBanner } from '../molecules/ConnectionBanner'
import { Header } from '../organisms/Header'

export function PageLayout({ children }: { children: ReactNode }) {
  return (
    <div className="layout">
      <Header />
      <main className="main">
        <ConnectionBanner />
        {children}
      </main>
      <footer className="footer muted">TicketLite demo on a Raspberry Pi k3s cluster</footer>
    </div>
  )
}
