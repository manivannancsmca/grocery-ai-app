import { z } from 'zod'

/**
 * The production Content-Security-Policy has no 'unsafe-eval' (frontend/grocery-web/nginx). Zod 4 otherwise probes
 * `new Function` to JIT-compile object parsers; the probe is caught, but the browser still reports a CSP violation
 * on every page load. Zod decides when a schema is created, so this module must be imported before any schema:
 * it is the first import in main.tsx.
 */
z.config({ jitless: true })
