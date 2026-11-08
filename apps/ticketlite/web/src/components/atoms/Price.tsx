const yen = new Intl.NumberFormat('ja-JP', { style: 'currency', currency: 'JPY' })

/** Prices are stored in cents of yen (x100) to keep integer money math. */
export function Price({ cents }: { cents: number }) {
  return <span className="price">{yen.format(cents / 100)}</span>
}
