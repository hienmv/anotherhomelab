import type { ReactNode } from 'react'

type Tone = 'hot' | 'muted' | 'success' | 'danger' | 'info'

export function Badge({ tone = 'info', children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`badge badge--${tone}`}>{children}</span>
}
