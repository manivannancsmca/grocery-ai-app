import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/problemDetail'
import { createTestQueryClient } from '../../test/createTestQueryClient'
import { fetchIdentityHealth } from './api'
import { BackendHealthCard } from './BackendHealthCard'

vi.mock('./api', () => ({ fetchIdentityHealth: vi.fn() }))

const fetchHealth = vi.mocked(fetchIdentityHealth)

function renderCard() {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <BackendHealthCard />
    </QueryClientProvider>,
  )
}

describe('BackendHealthCard', () => {
  beforeEach(() => {
    fetchHealth.mockReset()
  })

  it('shows a loading state while the health check runs', () => {
    fetchHealth.mockReturnValue(new Promise(() => {}))
    renderCard()
    expect(screen.getByText('Checking backend…')).toBeInTheDocument()
  })

  it('shows UP when the identity service is healthy', async () => {
    fetchHealth.mockResolvedValue({ status: 'UP' })
    renderCard()
    expect(await screen.findByText('UP')).toBeInTheDocument()
    expect(screen.getByText(/reachable through the API gateway/i)).toBeInTheDocument()
  })

  it('shows DOWN when the service reports it is unhealthy', async () => {
    fetchHealth.mockResolvedValue({ status: 'DOWN' })
    renderCard()
    expect(await screen.findByText('DOWN')).toBeInTheDocument()
    expect(screen.getByText(/not healthy/i)).toBeInTheDocument()
  })

  it('shows an error with the correlation reference when the gateway is unreachable', async () => {
    fetchHealth.mockRejectedValue(
      new ApiError('Cannot reach the server. Check that the API gateway is running.', {
        kind: 'network',
        status: null,
        problem: null,
        correlationId: 'abc-123',
      }),
    )
    renderCard()
    expect(await screen.findByRole('alert')).toHaveTextContent('Backend unreachable')
    expect(screen.getByText(/check that the api gateway is running/i)).toBeInTheDocument()
    expect(screen.getByText('Reference: abc-123')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})
