const dateFmt = new Intl.DateTimeFormat('en-GB', {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  timeZone: 'Asia/Tokyo',
})

export function ConcertMeta({ venue, startsAt }: { venue: string; startsAt: string }) {
  return (
    <p className="meta">
      <span>📅 {dateFmt.format(new Date(startsAt))}</span>
      <span>📍 {venue}</span>
    </p>
  )
}
