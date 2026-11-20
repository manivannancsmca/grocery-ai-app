import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { aiStatus, SHOPPING_AGENT } from '../../test/aiFixtures'
import { cartView } from '../../test/cartFixtures'
import { mockApi } from '../../test/mockApi'
import { mockChatStream } from '../../test/mockChatStream'
import { pageOf } from '../../test/orderFixtures'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'

function customer(agents = [SHOPPING_AGENT]) {
  signInAs(['CUSTOMER'])
  return mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /ai/agents': { status: 200, data: agents },
    'GET /ai/status': { status: 200, data: aiStatus() },
    'GET /ai/conversations': pageOf([]),
    'GET /cart': { status: 200, data: cartView([]) },
    'GET /orders': pageOf([]),
  })
}

describe('Ask AI in the top bar (phones)', () => {
  it('sits in the top bar next to the bell and opens the same assistant panel', async () => {
    const user = userEvent.setup()
    customer()
    renderApp('/orders')

    const askAi = await screen.findByRole('button', { name: 'Ask AI' })
    expect(askAi).toHaveAttribute('aria-haspopup', 'dialog')
    const topBar = askAi.closest('header')
    expect(topBar).not.toBeNull()
    expect(within(topBar).getByRole('button', { name: 'Notifications' })).toBeInTheDocument()
    // Phones only: from `md` the launcher docked in the left menu takes over, and it never floats on phones.
    expect(askAi).toHaveClass('md:hidden')
    expect(screen.getByRole('button', { name: 'Open the assistant' })).toHaveClass('hidden', 'md:flex')

    await user.click(askAi)
    expect(await screen.findByRole('dialog', { name: 'Shopping assistant' })).toBeInTheDocument()
  })

  it('shows that the assistant is answering', async () => {
    const user = userEvent.setup()
    customer()
    const chat = mockChatStream()
    renderApp('/orders')

    await user.click(await screen.findByRole('button', { name: 'Ask AI' }))
    const panel = await screen.findByRole('dialog', { name: 'Shopping assistant' })
    await user.type(within(panel).getByRole('textbox', { name: 'Message the assistant' }), 'find basmati rice')
    await user.click(within(panel).getByRole('button', { name: 'Send' }))

    expect(await screen.findByRole('button', { name: 'Ask AI (answering)', hidden: true })).toBeInTheDocument()
    const stream = await chat.connection()
    stream.conversation('c1')
    stream.token('Here are the basmati rice packs near you.')
    stream.done()
    expect(await screen.findByRole('button', { name: 'Ask AI', hidden: true })).toBeInTheDocument()
  })

  it('is not there when the user has no assistant', async () => {
    customer([])
    renderApp('/orders')
    await screen.findByRole('button', { name: 'Notifications' })
    expect(screen.queryByRole('button', { name: /Ask AI/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Open the assistant/ })).not.toBeInTheDocument()
  })
})
