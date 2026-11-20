import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { placeTooltip } from './placeTooltip'
import { TooltipLayer } from './TooltipLayer'

const original = window.matchMedia

/** A desktop with a mouse: `(hover: hover) and (pointer: fine)` matches. */
function mouseDevice(matches = true) {
  window.matchMedia = vi.fn((query) => ({ matches, media: query, addEventListener() {}, removeEventListener() {} }))
}

afterEach(() => {
  window.matchMedia = original
})

describe('TooltipLayer', () => {
  it('replaces the browser tooltip of a titled element with its own, and puts everything back on leaving', async () => {
    mouseDevice()
    render(
      <>
        <TooltipLayer />
        <button type="button" title="Export every product as CSV">
          Export
        </button>
      </>,
    )
    const button = screen.getByRole('button', { name: 'Export' })

    fireEvent.mouseOver(button)
    // The title is moved aside at once, so the browser never shows its own tooltip.
    expect(button).not.toHaveAttribute('title')
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()

    const tooltip = await screen.findByRole('tooltip')
    expect(tooltip).toHaveTextContent('Export every product as CSV')
    expect(button).toHaveAttribute('aria-describedby', 'app-tooltip')
    expect(screen.getByRole('button', { name: 'Export' })).toHaveAccessibleDescription('Export every product as CSV')

    fireEvent.mouseOut(button, { relatedTarget: document.body })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
    expect(button).toHaveAttribute('title', 'Export every product as CSV')
    expect(button).not.toHaveAttribute('aria-describedby')
  })

  it('keeps the name of an icon-only button whose title is its name', async () => {
    mouseDevice()
    render(
      <>
        <TooltipLayer />
        <button type="button" title="Grid view">
          <svg aria-hidden="true" />
        </button>
      </>,
    )
    const button = screen.getByRole('button', { name: 'Grid view' })

    fireEvent.mouseOver(button)
    expect(screen.getByRole('button', { name: 'Grid view' })).toBe(button)
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Grid view')

    fireEvent.mouseOut(button, { relatedTarget: document.body })
    expect(button).not.toHaveAttribute('aria-label')
    expect(button).toHaveAttribute('title', 'Grid view')
  })

  it('shows a data-tooltip on keyboard focus and hides it with Escape', async () => {
    mouseDevice()
    render(
      <>
        <TooltipLayer />
        <a href="/vendor/pricing" data-tooltip="Set the price on the Pricing page">
          No price
        </a>
      </>,
    )
    const link = screen.getByRole('link', { name: 'No price' })

    fireEvent.keyDown(document.body, { key: 'Tab' })
    fireEvent.focusIn(link)
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Set the price on the Pricing page')

    fireEvent.keyDown(document.body, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('stays out of the way on touch screens', () => {
    mouseDevice(false)
    render(
      <>
        <TooltipLayer />
        <button type="button" title="Archive">
          Archive
        </button>
      </>,
    )
    const button = screen.getByRole('button', { name: 'Archive' })
    fireEvent.mouseOver(button)
    expect(button).toHaveAttribute('title', 'Archive')
  })
})

describe('placeTooltip', () => {
  const viewport = { width: 1000, height: 800 }
  const size = { width: 120, height: 30 }

  it('centres the tooltip above the element', () => {
    expect(placeTooltip({ top: 400, bottom: 432, left: 500, width: 100 }, size, viewport)).toEqual({
      top: 362,
      left: 490,
      side: 'top',
      arrowLeft: 60,
    })
  })

  it('goes below an element near the top of the screen', () => {
    expect(placeTooltip({ top: 20, bottom: 52, left: 500, width: 100 }, size, viewport)).toMatchObject({
      top: 60,
      side: 'bottom',
    })
  })

  it('stays inside the screen at the edges, its arrow still pointing at the element', () => {
    const place = placeTooltip({ top: 400, bottom: 432, left: 960, width: 32 }, size, viewport)
    expect(place.left).toBe(1000 - 120 - 8)
    expect(place.arrowLeft).toBe(976 - 872)
  })
})
