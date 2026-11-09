import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { QuantityPicker } from './QuantityPicker'

describe('QuantityPicker', () => {
  it('cannot go below 1', () => {
    render(<QuantityPicker value={1} max={2} onChange={() => {}} />)
    expect(screen.getByRole('button', { name: 'Fewer' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'More' })).toBeEnabled()
  })

  it('cannot go above max', () => {
    render(<QuantityPicker value={2} max={2} onChange={() => {}} />)
    expect(screen.getByRole('button', { name: 'More' })).toBeDisabled()
  })

  it('reports the new value', async () => {
    const onChange = vi.fn()
    render(<QuantityPicker value={1} max={6} onChange={onChange} />)
    await userEvent.click(screen.getByRole('button', { name: 'More' }))
    expect(onChange).toHaveBeenCalledWith(2)
  })
})
