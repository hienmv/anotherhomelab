import { Spinner } from '../atoms/Spinner'

export type Status = { kind: 'info' | 'success' | 'error' | 'pending'; text: string }

export function StatusMessage({ status }: { status: Status | null }) {
  if (!status) return null
  return (
    <div className={`status status--${status.kind}`} role="status">
      {status.kind === 'pending' && <Spinner />} {status.text}
    </div>
  )
}
