import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api'
import { LoginForm } from './LoginForm'

async function fillAndSubmit(username: string, password: string) {
  await userEvent.type(screen.getByLabelText('Username'), username)
  await userEvent.type(screen.getByLabelText('Password'), password)
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }))
}

describe('LoginForm', () => {
  it('submits the credentials', async () => {
    const onLogin = vi.fn().mockResolvedValue(undefined)
    render(<LoginForm onLogin={onLogin} />)
    await fillAndSubmit('alice', 'alice123')
    expect(onLogin).toHaveBeenCalledWith('alice', 'alice123')
  })

  it('shows a friendly error for wrong credentials', async () => {
    const onLogin = vi.fn().mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS'))
    render(<LoginForm onLogin={onLogin} />)
    await fillAndSubmit('alice', 'wrong')
    expect(await screen.findByText('Wrong username or password.')).toBeInTheDocument()
  })

  it('lists the sample accounts', () => {
    render(<LoginForm onLogin={vi.fn()} />)
    expect(screen.getByText('alice / alice123')).toBeInTheDocument()
  })
})
