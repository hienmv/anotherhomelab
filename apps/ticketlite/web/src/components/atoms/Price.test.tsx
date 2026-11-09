import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Price } from './Price'

describe('Price', () => {
  it('shows yen from cents (stored x100 to keep integer money math)', () => {
    render(<Price cents={1_200_000} />)
    expect(screen.getByText(/12,000/)).toBeInTheDocument()
  })
})
