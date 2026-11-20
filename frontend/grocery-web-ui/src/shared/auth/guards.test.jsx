import { screen } from '@testing-library/react'
import { Outlet } from 'react-router'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'
import { RequireRole } from './guards'
import { ADMIN_ROLES } from './roles'
import { resetSessionForTests } from './session'

const routes = [
  {
    path: '/',
    element: <Outlet />,
    children: [
      { index: true, element: <p>Storefront</p> },
      { path: 'login', element: <p>Login page</p> },
      {
        path: 'vendor',
        element: (
          <RequireRole roles={['VENDOR']}>
            <p>Vendor home</p>
          </RequireRole>
        ),
      },
      {
        path: 'admin',
        element: <RequireRole roles={ADMIN_ROLES} />,
        children: [
          { index: true, element: <p>Admin home</p> },
          {
            path: 'users',
            element: (
              <RequireRole roles={ADMIN_ROLES} permission="users.view">
                <p>Users page</p>
              </RequireRole>
            ),
          },
        ],
      },
    ],
  },
]

describe('RequireRole', () => {
  it('sends signed-out visitors to /login and remembers where they were going', async () => {
    const { router } = renderApp('/admin/users?status=SUSPENDED', routes)

    expect(await screen.findByText('Login page')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.state).toEqual({ from: '/admin/users?status=SUSPENDED' })
  })

  it('redirects a user without the role to their own home page', async () => {
    signInAs(['VENDOR'])
    const { router } = renderApp('/admin', routes)

    expect(await screen.findByText('Vendor home')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/vendor')
    expect(screen.queryByText('Admin home')).not.toBeInTheDocument()
  })

  it('redirects an admin whose sub-role lacks the permission to the admin home', async () => {
    signInAs(['FINANCE_ADMIN'])
    const { router } = renderApp('/admin/users', routes)

    expect(await screen.findByText('Admin home')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/admin')
  })

  it('lets an admin with the permission in', async () => {
    signInAs(['SUPPORT'])
    renderApp('/admin/users', routes)

    expect(await screen.findByText('Users page')).toBeInTheDocument()
  })

  it('waits (spinner) while the session is still being restored', () => {
    resetSessionForTests('initializing')
    renderApp('/vendor', routes)

    expect(screen.getByRole('status')).toHaveTextContent('Loading page')
    expect(screen.queryByText('Login page')).not.toBeInTheDocument()
  })
})
