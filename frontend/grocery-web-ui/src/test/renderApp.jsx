import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { AppProviders } from '../app/providers'
import { routes as appRoutes } from '../app/router'
import { createTestQueryClient } from './createTestQueryClient'

/** Renders a route tree (the whole app by default) at `path` with every provider. */
export function renderApp(path, routes = appRoutes) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const queryClient = createTestQueryClient()
  const result = render(
    <AppProviders queryClient={queryClient}>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return { ...result, router, queryClient }
}
