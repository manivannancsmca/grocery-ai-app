import { useState } from 'react'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from './providers'
import { createAppRouter } from './router'

export function App() {
  // Created once per app instance (not at module load) so tests and HMR get a fresh router.
  const [router] = useState(createAppRouter)

  return (
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>
  )
}
