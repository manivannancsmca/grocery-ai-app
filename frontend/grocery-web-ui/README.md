# grocery-web-ui — FreshCart frontend (JavaScript)

This is the FreshCart web app (groceries and restaurant meals) rewritten in **plain JavaScript**. It is a copy of
`../grocery-web` with all TypeScript removed. The screens, routes, API calls and behaviour are the same as the original,
and so is the styling, which is Tailwind CSS in both apps.

One React application has a lazily loaded area per stakeholder: Customer, Vendor, Restaurant Owner, Delivery Partner
and Admin. Everyone uses the same sign-in page.

## Stack

React 19 · JavaScript (ES modules, `.js` / `.jsx`) · Vite 8 · Tailwind CSS 4 · React Router 7 · TanStack Query 5 ·
axios · React Hook Form + Zod · Leaflet (maps) · Vitest + Testing Library · ESLint (flat config)

Versions are pinned exactly in `package.json`, and `package-lock.json` is committed.

## Run it on your machine

You need Node.js 24 or newer, and the API gateway must be running (by default on `http://localhost:8080`).

```bash
npm install
npm run dev
# open http://localhost:6421
```

The original app uses port 6420, so both apps can run at the same time. If your gateway runs somewhere else,
for example the Docker gateway published on 8090:

```bash
VITE_PROXY_TARGET=http://localhost:8090 npm run dev
```

## Run it in Docker (no Node.js on the host)

With the root Docker Compose setup (service `frontend-ui`, its own profile, so it never starts with `all`):

```bash
docker compose -f infrastructure/local/docker-compose.yml --profile frontend-ui up -d frontend-ui
# open http://localhost:6421
```

When the Spring Boot services run on the host (`host-run/start-all.cmd`), start it with
`VITE_PROXY_TARGET=http://host.docker.internal:8080`.

Standalone, from this folder:

```bash
docker build -t grocery-web-ui-dev .
docker run --rm -p 6421:6421 \
  -e VITE_PROXY_TARGET=http://host.docker.internal:8080 \
  -e VITE_USE_POLLING=true \
  -v "$(pwd):/app" -v grocery-web-ui-node-modules:/app/node_modules \
  grocery-web-ui-dev
```

`Dockerfile.prod` builds the production image: the Vite build served by nginx, with `/api` proxied to `API_UPSTREAM`.
The `nginx/` folder is the same as in the original app.

## Scripts

| Script | What it does |
|---|---|
| `npm run dev` | Vite dev server on `0.0.0.0:6421` with hot reload; `/api/**` is proxied to the gateway |
| `npm run build` | Production build into `dist/` |
| `npm run preview` | Serve the production build on port 4174 |
| `npm test` | Vitest in watch mode (`npm test -- --run` for a single run) |
| `npm run lint` | ESLint (JavaScript, React Hooks and React Refresh rules) |

## Environment variables (dev server)

| Variable | Default | Purpose |
|---|---|---|
| `VITE_PROXY_TARGET` | `http://localhost:8080` | Where the dev server forwards `/api/**` (the API gateway). |
| `VITE_USE_POLLING` | unset (`false`) | Set to `true` in containers on Windows bind mounts, which don't send file-change events. |
| `VITE_DEV_ACCOUNTS` | unset (shown) | `false` hides the "Dev only" accounts section on the sign-in screen. |
| `VITE_LOCATION_SIMULATOR` | unset | `true` keeps the delivery location simulator in a build (it is always available in dev). |

The browser always calls relative `/api/...` URLs, so dev needs no CORS setup, and in production the SPA and the
gateway share one origin.

## Folder structure

```
src/
├── main.jsx             # entry point: configures Zod, then renders <App />
├── index.css            # Tailwind import, theme (fonts, colours), animations, a few base rules
├── app/                 # App.jsx, router.jsx (route tree), providers.jsx, queryClient.js
│   ├── layout/          # AppLayout (header, sidebar, mobile menu), RoleNav, UserMenu, navigation config
│   └── pages/           # NotFound, RouteError
├── shared/              # code used by every role
│   ├── api/             # httpClient (axios, Bearer token, refresh on 401), ApiError, paging helpers
│   ├── auth/            # session store, AuthProvider / useAuth, route guards, roles, permissions
│   ├── realtime/        # Server-Sent Events parser + client
│   ├── components/      # ui/ building blocks (Button, Card, Input, Dialog, DataTable, …), charts/, map/, toast/
│   ├── export/          # CSV / Excel / PDF export button and download progress
│   ├── forms/           # Zod validators and server field-error mapping
│   ├── hooks/           # small reusable hooks (useDebouncedValue, useCurrentPosition, …)
│   ├── icons/           # <Icon name="…" /> and its artwork
│   └── utils/
├── features/            # one folder per backend area: api.js (calls + query keys), hooks.js, components
│   ├── auth/  customer/  vendor/  restaurant/  delivery/  stakeholders/  users/
│   ├── catalog/  cart/  orders/  payments/  returns/  reviews/  complaints/  promotions/ …
│   ├── notifications/  realtime/  deliveries/  order-tracking/  operations/  ai/  system/
├── roles/               # one folder per stakeholder: routes.jsx (guarded) + pages/
│   ├── customer/  vendor/  restaurant/  delivery/  admin/
└── test/                # Vitest setup, in-memory API (mockApi.js), SSE mocks, fixtures, render helpers
```

## How the code fits together

- **Pages** live in `roles/<role>/pages`. Each role's `routes.jsx` is loaded lazily and wrapped in `RequireRole`.
- **Server data** goes through TanStack Query and the shared `httpClient`. Every feature's `api.js` holds its calls
  and its query keys, and `hooks.js` wraps them in `useQuery` / `useMutation`. Mutations invalidate those keys.
- **Forms** use React Hook Form with a Zod schema (`schema.js` next to the form). Server validation errors are put
  on the matching fields (`shared/forms/serverErrors.js`).
- **Errors**: every failed API call becomes an `ApiError` (kind, HTTP status, `errorCode`, field errors,
  correlation ID).
- **Auth**: the access token is kept in memory only (`shared/auth/session.js`). The refresh token is an httpOnly
  cookie. On start the app calls `POST /api/auth/refresh` to restore the session, and on a `401` the HTTP client
  refreshes once and retries the request.
- **Live updates** come over Server-Sent Events (`features/realtime`). One map (`invalidation.js`) says which
  queries to refetch for each server signal.
- **Styling** uses Tailwind classes in the JSX. Build pages from `shared/components/ui` (PageHeader, Card,
  StatCard, DataTable, Pagination, …) rather than hand-styling boxes. `cn()` (`shared/utils/cn.js`) joins class names.
- **Tests** never touch the network. `src/test/mockApi.js` swaps the axios adapter for an in-memory router, and
  `src/test/mockEventStream.js` fakes the SSE stream.

## Types as JSDoc comments

There is no TypeScript, but the types are still written down, as JSDoc comments. VS Code reads them, so you get
autocomplete and hover help for API data and component props. They are only comments: nothing checks them at build
time and they never change what the code does.

```js
/**
 * @typedef {object} FormFieldProps
 * @property {ReactNode} label
 * @property {string} [error]
 */

/** @param {FormFieldProps} props */
export function FormField({ label, error }) { ... }
```

- `[error]` in square brackets means the field is optional.
- `@typedef` describes the shape of an object (API responses live in each feature's `api.js`).
- `@param` / `@returns` describe a function's inputs and result; `@type` the type of a constant.
- `/** @typedef {import('./api').CartView} CartView */` borrows a type defined in another file.
- When you add or change a field, update the `@typedef` next to it so the hints stay right.

## What changed from the TypeScript app

- `.ts` / `.tsx` became `.js` / `.jsx`. The type annotations became JSDoc comments, so the logic, comments,
  Tailwind classes and tests are the same as before.
- The largest component files were split into smaller ones next to them. For example, Admin → Reports now has one
  file per section (`features/admin-reports/*Section.jsx`), and the AI assistant panel, cart page, order list,
  approval card and partner performance card each moved their sub-components into their own files. Each new file
  starts with a one-line comment saying what it holds. The old file names still export the same things, so imports
  elsewhere did not change.
- TypeScript, `@types/*` and `typescript-eslint` were removed from the dependencies, and `tsconfig*.json` is gone.
  `npm run build` is now just `vite build`.
- Product cards (`ProductTile`, `VendorProductCard`) and the "Shop by category" tiles have darker borders than the
  original, so they stand out from the page background (`border-slate-300`; each category tile uses a darker shade
  of its own colour, set in `categoryStyle.js`).
- The dev server runs on port **6421** (preview on 4174), so it does not clash with the original app.
- CI: `Jenkinsfile` runs the shared frontend pipeline with `typeCheck: false` (no tsc) and `publish: false`, so the
  app is linted, tested, built and scanned but never pushed or deployed in place of `grocery-web`.
