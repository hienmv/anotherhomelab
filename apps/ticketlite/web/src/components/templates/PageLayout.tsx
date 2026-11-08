import type { ReactNode } from 'react'
import { Header } from '../organisms/Header'

export function PageLayout({ children }: { children: ReactNode }) {
  return (
    <div className="layout">
      <Header />
      <main className="main">{children}</main>
      <footer className="footer muted">TicketLite demo on a Raspberry Pi k3s cluster</footer>
    </div>
  )
}
