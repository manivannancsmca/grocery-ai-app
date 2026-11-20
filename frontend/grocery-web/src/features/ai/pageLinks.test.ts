import { describe, expect, it } from 'vitest'
import type { ToolActivity } from './api'
import { answerLinks, isAgentPage, MAX_ANSWER_LINKS, toPageLinks } from './pageLinks'

function tool(links: ToolActivity['links'], status = 'SUCCEEDED'): ToolActivity {
  return { invocationId: `i-${Math.random()}`, tool: 'getCart', label: 'Checked the cart', status, summary: null, links }
}

describe('isAgentPage', () => {
  it("accepts the assistant's own pages, with their query strings and ids", () => {
    expect(isAgentPage('shopping', '/cart')).toBe(true)
    expect(isAgentPage('shopping', '/search?q=basmati+rice&maxPrice=50')).toBe(true)
    expect(isAgentPage('shopping', '/orders/01a1d3de-9cc4-7074-b1f3-2dd6a0a603ca')).toBe(true)
    expect(isAgentPage('admin-bi', '/admin/orders?order=01a1d3de-9cc4-7074-b1f3-2dd6a0a603ca')).toBe(true)
    expect(isAgentPage('vendor', '/vendor/inventory?q=Toor+Dal&filter=LOW_STOCK')).toBe(true)
    expect(isAgentPage('delivery', '/admin/deliveries?noPartner=1')).toBe(true)
  })

  it("refuses another area's pages, unknown paths and other websites", () => {
    expect(isAgentPage('shopping', '/admin/orders')).toBe(false)
    expect(isAgentPage('vendor', '/restaurant/menu')).toBe(false)
    expect(isAgentPage('shopping', '/cart/secret')).toBe(false)
    expect(isAgentPage('shopping', 'https://evil.example/cart')).toBe(false)
    expect(isAgentPage('shopping', '//evil.example/cart')).toBe(false)
    expect(isAgentPage('shopping', '/\\evil.example')).toBe(false)
    expect(isAgentPage('shopping', 'javascript:alert(1)')).toBe(false)
    expect(isAgentPage('unknown-agent', '/cart')).toBe(false)
  })
})

describe('toPageLinks', () => {
  it('keeps well-formed links only', () => {
    expect(toPageLinks([{ label: 'View my cart', path: '/cart' }, { label: '', path: '/x' }, { path: '/y' }, 'z', null])).toEqual([
      { label: 'View my cart', path: '/cart' },
    ])
    expect(toPageLinks(undefined)).toEqual([])
  })
})

describe('answerLinks', () => {
  it('lists the pages of successful tool calls once each', () => {
    const links = answerLinks(
      'shopping',
      [
        tool([{ label: 'See all results for "rice"', path: '/search?q=rice' }]),
        tool([{ label: 'View my cart', path: '/cart' }]),
        tool([{ label: 'View my cart', path: '/cart' }]),
        tool([{ label: 'Never ran', path: '/orders' }], 'BLOCKED'),
      ],
      'Added rice to your cart.',
    )

    expect(links).toEqual([
      { label: 'See all results for "rice"', path: '/search?q=rice' },
      { label: 'View my cart', path: '/cart' },
    ])
  })

  it('leaves out pages the text links to already, and pages that are not the assistant’s', () => {
    const links = answerLinks(
      'shopping',
      [tool([{ label: 'View my cart', path: '/cart' }, { label: 'Admin', path: '/admin/orders' }, { label: 'Checkout', path: '/checkout' }])],
      'Your cart is ready: [My cart](/cart).',
    )

    expect(links).toEqual([{ label: 'Checkout', path: '/checkout' }])
  })

  it(`shows at most ${MAX_ANSWER_LINKS}`, () => {
    const many = Array.from({ length: 6 }, (_, index) => ({ label: `Order ${index}`, path: `/orders/${index}` }))
    expect(answerLinks('shopping', [tool(many)], '')).toHaveLength(MAX_ANSWER_LINKS)
  })
})
