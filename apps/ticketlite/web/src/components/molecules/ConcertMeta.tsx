declare const __SITE_TIMEZONE__: string // vite.config.ts

const dateFmt = new Intl.DateTimeFormat('en-GB', {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  timeZone: __SITE_TIMEZONE__,
})

export function ConcertMeta({ venue, startsAt }: { venue: string; startsAt: string }) {
  return (
    <p className="meta">
      <span>📅 {dateFmt.format(new Date(startsAt))}</span>
      <span>📍 {venue}</span>
    </p>
  )
}
