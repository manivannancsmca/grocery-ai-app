import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Icon, iconMarkup } from '.'
import { ICON_ART, ICON_SETS } from './registry'

const HEX = /^#[0-9a-f]{6}$/

describe('icon registry', () => {
  it('never uses one name in two sets (a later set would silently replace the earlier icon)', () => {
    const names = Object.values(ICON_SETS).flatMap((set) => Object.keys(set))
    expect(names.length).toBe(new Set(names).size)
    expect(Object.keys(ICON_ART)).toHaveLength(names.length)
  })

  it('draws every picture in palette colours, never in the text colour', () => {
    for (const set of [
      ICON_SETS.STATUS_ART,
      ICON_SETS.EVERYDAY_ART,
      ICON_SETS.COMMERCE_ART,
      ICON_SETS.BUSINESS_ART,
      ICON_SETS.FOOD_ART,
      ICON_SETS.BADGED_ART,
    ]) {
      for (const [name, art] of Object.entries(set)) {
        expect(art.length, name).toBeGreaterThan(0)
        for (const shape of art) {
          expect(shape.d, name).toMatch(/^[Mm][\d.-]/)
          expect(shape.fill ?? shape.stroke, name).toBeDefined()
          if (shape.fill) expect(shape.fill, name).toMatch(HEX)
          if (shape.stroke) expect(shape.stroke, name).toMatch(HEX)
        }
        // At least two colours: these are the colourful icons.
        expect(new Set(art.map((shape) => shape.fill ?? shape.stroke)).size, name).toBeGreaterThan(1)
      }
    }
  })

  it('draws the control glyphs in the text colour only, so they adapt to any button', () => {
    for (const [name, art] of Object.entries(ICON_SETS.GLYPH_ART)) {
      for (const shape of art) expect(shape.fill ?? shape.stroke, name).toBe('currentColor')
    }
  })
})

describe('Icon', () => {
  it('renders a decorative svg with one path per shape', () => {
    const { container } = render(<Icon name="cart" />)
    const svg = container.querySelector('svg')
    expect(svg).toHaveAttribute('aria-hidden', 'true')
    expect(svg).toHaveAttribute('viewBox', '0 0 24 24')
    expect(svg?.querySelectorAll('path')).toHaveLength(ICON_ART.cart.length)
  })

  it('is size-5 unless the caller sets a size', () => {
    const { container, rerender } = render(<Icon name="home" className="mt-1" />)
    expect(container.querySelector('svg')).toHaveClass('size-5', 'mt-1', 'shrink-0')
    rerender(<Icon name="home" className="size-3.5" />)
    expect(container.querySelector('svg')).toHaveClass('size-3.5')
    expect(container.querySelector('svg')).not.toHaveClass('size-5')
  })

  it('shrinks the picture and adds the status mark for badged icons', () => {
    const { container } = render(<Icon name="homeCheck" />)
    const paths = [...container.querySelectorAll('path')]
    expect(paths.length).toBeGreaterThan(ICON_ART.home.length)
    expect(paths[0]).toHaveAttribute('transform', 'scale(0.84)')
    expect(paths.at(-1)).not.toHaveAttribute('transform')
  })
})

describe('iconMarkup', () => {
  it('renders the same art as an HTML string (map markers)', () => {
    const name = 'scooter'
    const markup = iconMarkup(name, 'size-5')
    expect(markup).toMatch(/^<svg class="size-5" viewBox="0 0 24 24" aria-hidden="true" focusable="false">/)
    expect(markup.match(/<path /g)).toHaveLength(ICON_ART[name].length)
    expect(markup).toContain('stroke-linecap="round"')
  })
})
