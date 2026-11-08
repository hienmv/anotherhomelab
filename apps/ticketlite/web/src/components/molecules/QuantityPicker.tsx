import { Button } from '../atoms/Button'

interface Props {
  value: number
  max: number
  onChange: (value: number) => void
  disabled?: boolean
}

export function QuantityPicker({ value, max, onChange, disabled }: Props) {
  return (
    <div className="qty" aria-label="Number of tickets">
      <Button variant="ghost" disabled={disabled || value <= 1} onClick={() => onChange(value - 1)} aria-label="Fewer">
        −
      </Button>
      <span className="qty__value">{value}</span>
      <Button variant="ghost" disabled={disabled || value >= max} onClick={() => onChange(value + 1)} aria-label="More">
        +
      </Button>
    </div>
  )
}
