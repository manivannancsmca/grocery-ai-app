import { act, fireEvent, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CategoryNode } from '../../../features/catalog/api'
import { SLIDE_INTERVAL_MS } from '../../../features/promotions/HeroSlideshow'
import { mockApi } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'

// The catalog has a Fruits category, but no "Rice and Rice Products": that slide falls back to a search.
const categories: CategoryNode[] = [
  { id: 'c-fruits', name: 'Fruits', description: null, imageUrl: null, productCount: 9, children: [] },
  { id: 'c-baby', name: 'baby care', description: null, imageUrl: null, productCount: 4, children: [] },
]

function storefrontApi() {
  return mockApi({
    'GET /health/identity': { status: 200, data: { status: 'UP' } },
    'GET /catalog/categories': { status: 200, data: categories },
    'GET /search/restaurants': { status: 200, data: { content: [], page: 0, size: 6, totalElements: 0, totalPages: 0, facets: { cuisines: [] } } },
    'GET /search/products': {
      status: 200,
      data: { content: [], page: 0, size: 24, totalElements: 0, totalPages: 0, facets: { categories: [], brands: [], foodTypes: [] } },
    },
  })
}

/** The slide screen readers see now (the others are hidden), e.g. "2 of 5: Seasonal picks". */
function currentSlide(offers: HTMLElement) {
  const slides = within(offers).getAllByRole('group')
  expect(slides).toHaveLength(1)
  return slides[0]!.getAttribute('aria-label')
}

function currentDot(offers: HTMLElement) {
  return within(offers)
    .getAllByRole('button', { name: /^Show offer/ })
    .find((dot) => dot.getAttribute('aria-current') === 'true')
    ?.getAttribute('aria-label')
}

const productPhotos = (container: HTMLElement) =>
  [...container.querySelectorAll('img')].map((img) => img.getAttribute('src')).filter((src) => src?.startsWith('/images/products/'))

const originalMatchMedia = window.matchMedia

/** A browser that allows motion (jsdom has no matchMedia: the slideshow then starts paused). */
function allowMotion() {
  window.matchMedia = vi.fn((query: string) => ({ matches: false, media: query }) as MediaQueryList)
}

afterEach(() => {
  vi.useRealTimers()
  window.matchMedia = originalMatchMedia
})

describe('Storefront hero slideshow', () => {
  it('keeps the hero content and turns the offers with the dots and arrows, linking each to its category', async () => {
    const user = userEvent.setup()
    storefrontApi()
    const { container } = renderApp('/')

    const offers = await screen.findByRole('region', { name: 'Offers' })
    // The hero's own content stays as it was.
    expect(screen.getByRole('heading', { level: 1, name: 'Fresh groceries and restaurant meals, delivered.' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Search groceries' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Create an account' })).toHaveAttribute('href', '/register')

    expect(currentSlide(offers)).toBe('1 of 5: Kitchen staples')
    expect(currentDot(offers)).toBe('Show offer 1 of 5: Kitchen staples')
    // No such category in this catalog: "Shop now" searches instead (wide panel and narrow strip alike).
    for (const link of within(offers).getAllByRole('link', { name: 'Shop now: Kitchen staples' })) {
      expect(link).toHaveAttribute('href', '/search?q=rice')
    }
    // Without matchMedia (as with reduced motion) nothing moves on its own, and a new slide is announced.
    expect(within(offers).getByRole('button', { name: 'Play the offers' })).toBeInTheDocument()
    expect(offers.querySelector('[aria-live]')).toHaveAttribute('aria-live', 'polite')
    // Photos load for the current slide and the next one only.
    expect(productPhotos(container)).toEqual(expect.arrayContaining(['/images/products/basmati-rice.jpg', '/images/products/pineapple.jpg']))
    expect(productPhotos(container)).not.toContain('/images/products/baby-food.jpg')

    await user.click(within(offers).getByRole('button', { name: 'Show offer 2 of 5: Seasonal picks' }))
    expect(currentSlide(offers)).toBe('2 of 5: Seasonal picks')
    expect(currentDot(offers)).toBe('Show offer 2 of 5: Seasonal picks')
    for (const link of within(offers).getAllByRole('link', { name: 'Shop now: Seasonal picks' })) {
      expect(link).toHaveAttribute('href', '/search?categoryId=c-fruits')
    }
    expect(productPhotos(container)).toContain('/images/products/baby-food.jpg')

    await user.click(within(offers).getByRole('button', { name: 'Next offer' }))
    expect(currentSlide(offers)).toBe('3 of 5: Baby care')
    // The category is matched by name, ignoring case.
    expect(within(offers).getAllByRole('link', { name: 'Shop now: Baby care' })[0]).toHaveAttribute('href', '/search?categoryId=c-baby')

    await user.click(within(offers).getByRole('button', { name: 'Previous offer' }))
    await user.click(within(offers).getByRole('button', { name: 'Previous offer' }))
    expect(currentSlide(offers)).toBe('1 of 5: Kitchen staples')
    // Round from the first to the last.
    await user.click(within(offers).getByRole('button', { name: 'Previous offer' }))
    expect(currentSlide(offers)).toBe('5 of 5: Home care')

    // "Shop now" opens the search.
    await user.click(within(offers).getAllByRole('link', { name: 'Shop now: Home care' })[0]!)
    expect(await screen.findByRole('heading', { name: 'Results for “cleaner”' })).toBeInTheDocument()
  })

  it('turns by itself when motion is allowed, holds under the pointer and stops when paused', async () => {
    allowMotion()
    storefrontApi()
    renderApp('/')

    const offers = await screen.findByRole('region', { name: 'Offers' })
    expect(within(offers).getByRole('button', { name: 'Pause the offers' })).toBeInTheDocument()
    // Moving: screen readers are not interrupted by each turn.
    expect(offers.querySelector('[aria-live]')).toHaveAttribute('aria-live', 'off')

    // From here on the slideshow's countdowns are fake (a new slide starts one).
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    fireEvent.click(within(offers).getByRole('button', { name: 'Next offer' }))
    expect(currentSlide(offers)).toBe('2 of 5: Seasonal picks')

    await act(async () => vi.advanceTimersByTime(SLIDE_INTERVAL_MS - 100))
    expect(currentSlide(offers)).toBe('2 of 5: Seasonal picks')
    await act(async () => vi.advanceTimersByTime(100))
    expect(currentSlide(offers)).toBe('3 of 5: Baby care')

    // A pointer resting on the offers holds them; leaving starts the slide's full time again.
    fireEvent.pointerOver(offers)
    await act(async () => vi.advanceTimersByTime(SLIDE_INTERVAL_MS * 2))
    expect(currentSlide(offers)).toBe('3 of 5: Baby care')
    fireEvent.pointerOut(offers, { relatedTarget: document.body })
    await act(async () => vi.advanceTimersByTime(SLIDE_INTERVAL_MS))
    expect(currentSlide(offers)).toBe('4 of 5: Healthy mornings')

    // Pause stops it until play is pressed; after the last slide comes the first.
    fireEvent.click(within(offers).getByRole('button', { name: 'Pause the offers' }))
    expect(within(offers).getByRole('button', { name: 'Play the offers' })).toBeInTheDocument()
    expect(offers.querySelector('[aria-live]')).toHaveAttribute('aria-live', 'polite')
    await act(async () => vi.advanceTimersByTime(SLIDE_INTERVAL_MS * 3))
    expect(currentSlide(offers)).toBe('4 of 5: Healthy mornings')

    fireEvent.click(within(offers).getByRole('button', { name: 'Play the offers' }))
    // One slide per act: each new slide starts its own countdown once React has rendered it.
    await act(async () => vi.advanceTimersByTime(SLIDE_INTERVAL_MS))
    expect(currentSlide(offers)).toBe('5 of 5: Home care')
    await act(async () => vi.advanceTimersByTime(SLIDE_INTERVAL_MS))
    expect(currentSlide(offers)).toBe('1 of 5: Kitchen staples')
  })

  it('starts paused for visitors who prefer reduced motion', async () => {
    window.matchMedia = vi.fn((query: string) => ({ matches: query.includes('reduce'), media: query }) as MediaQueryList)
    storefrontApi()
    renderApp('/')

    const offers = await screen.findByRole('region', { name: 'Offers' })
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await act(async () => vi.advanceTimersByTime(SLIDE_INTERVAL_MS * 3))
    expect(currentSlide(offers)).toBe('1 of 5: Kitchen staples')
    expect(within(offers).getByRole('button', { name: 'Play the offers' })).toBeInTheDocument()
  })
})
