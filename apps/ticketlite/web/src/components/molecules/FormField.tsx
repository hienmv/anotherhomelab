import type { InputHTMLAttributes } from 'react'
import { Input } from '../atoms/Input'

interface Props extends InputHTMLAttributes<HTMLInputElement> {
  label: string
  id: string
}

export function FormField({ label, id, ...input }: Props) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <Input id={id} {...input} />
    </div>
  )
}
