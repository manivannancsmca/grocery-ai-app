import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AnswerLinks } from '../../../features/ai/AnswerLinks'
import { FormattedText } from '../../../features/ai/FormattedText'
import { Button } from './Button'
import { Dialog } from './Dialog'

function DrawerHost({ onClose = () => undefined }) {
  const [open, setOpen] = useState(true)
  return (
    <>
      <Button onClick={() => setOpen(true)}>Open details</Button>
      <Dialog
        open={open}
        variant="drawer"
        icon="receipt"
        title="Order GA-7K3F9Q2M"
        description="Paid · 3 items"
        footer={<Button>Refund</Button>}
        onClose={() => {
          onClose()
          setOpen(false)
        }}
      >
        <p>Order details</p>
      </Dialog>
    </>
  )
}

const original = { startViewTransition: document.startViewTransition, matchMedia: window.matchMedia }

afterEach(() => {
  Object.assign(document, { startViewTransition: original.startViewTransition })
  window.matchMedia = original.matchMedia
})

/** A browser with the View Transitions API: the update runs inside the transition, as the real one does. */
function installViewTransitions() {
  const startViewTransition = vi.fn((update) => {
    update()
    return {
      finished: Promise.resolve(),
      ready: Promise.resolve(),
      updateCallbackDone: Promise.resolve(),
      skipTransition: () => undefined,
    }
  })
  Object.assign(document, { startViewTransition })
  return startViewTransition
}

describe('Drawer (Dialog variant="drawer")', () => {
  it('shows its picture, title, description, content and pinned footer, and slides out through a view transition on close', async () => {
    const user = userEvent.setup()
    const startViewTransition = installViewTransitions()
    const onClose = vi.fn()
    render(<DrawerHost onClose={onClose} />)

    const drawer = screen.getByRole('dialog', { name: 'Order GA-7K3F9Q2M' })
    expect(drawer).toHaveAccessibleDescription('Paid · 3 items')
    expect(within(drawer).getByText('Order details')).toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: 'Refund' }).closest('footer')).not.toBeNull()
    expect(drawer.querySelector('header [aria-hidden="true"] svg')).not.toBeNull()

    await user.click(within(drawer).getByRole('button', { name: 'Close' }))
    // The closing drawer is named for its slide-out, and the update runs inside the transition: gone at once.
    expect(startViewTransition).toHaveBeenCalledTimes(1)
    expect(drawer.style.viewTransitionName).toBe('app-drawer')
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    // Focus goes back where it was.
    expect(document.body).toHaveFocus()
  })

  it('closes at once without the API, and with reduced motion, on Escape too', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    Object.assign(document, { startViewTransition: undefined })
    render(<DrawerHost onClose={onClose} />)
    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    const startViewTransition = installViewTransitions()
    window.matchMedia = vi.fn((query) => ({ matches: query.includes('reduce'), media: query }))
    await user.click(screen.getByRole('button', { name: 'Open details' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close' }))
    expect(startViewTransition).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

describe('AI answers — page links as buttons', () => {
  it('turns allowed [label](/path) links in the text, and the related pages, into buttons with an arrow', () => {
    const onNavigate = vi.fn()
    render(
      <MemoryRouter>
        <FormattedText
          text="Your order is on its way. [Track order GA-7K3F9Q2M](/orders/o1) or [visit](https://example.com)."
          links={{ allow: (path) => path.startsWith('/orders/'), onNavigate }}
        />
        <AnswerLinks links={[{ label: 'View my cart', path: '/cart' }]} onNavigate={onNavigate} />
      </MemoryRouter>,
    )

    const inline = screen.getByRole('link', { name: 'Track order GA-7K3F9Q2M' })
    expect(inline).toHaveAttribute('href', '/orders/o1')
    expect(inline).toHaveClass('rounded-lg', 'bg-emerald-600', 'text-white')
    expect(inline).not.toHaveClass('underline')
    expect(inline.querySelector('svg')).not.toBeNull()
    // Another website never becomes a link.
    expect(screen.queryByRole('link', { name: 'visit' })).not.toBeInTheDocument()

    const related = within(screen.getByRole('navigation', { name: 'Related pages' })).getByRole('link', {
      name: 'View my cart',
    })
    expect(related).toHaveAttribute('href', '/cart')
    expect(related).toHaveClass('rounded-lg', 'bg-emerald-600')
  })
})
