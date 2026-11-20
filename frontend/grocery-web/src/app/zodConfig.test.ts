import { describe, expect, it } from 'vitest'
import { z } from 'zod'
import './zodConfig'

describe('Zod configuration', () => {
  it('runs jitless, so the strict production CSP (no unsafe-eval) reports no violation', () => {
    expect(z.config().jitless).toBe(true)
    // Parsing still works without the JIT fast path.
    expect(z.object({ name: z.string() }).parse({ name: 'Ghee' })).toEqual({ name: 'Ghee' })
  })
})
