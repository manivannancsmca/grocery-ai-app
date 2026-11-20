import { RequireRole } from '../../shared/auth/guards'
import { ADMIN_ROLES } from '../../shared/auth/roles'
import { PageHeaderStyleContext } from '../../shared/components/ui'

/**
 * The admin area's frame (any admin role): its pages get the colourful "hero" page header. (The 5px corners are the
 * whole app's theme now — see the radius tokens in index.css — so the admin area needs nothing of its own for them.)
 */
export function AdminArea() {
  return (
    <PageHeaderStyleContext value="hero">
      <RequireRole roles={ADMIN_ROLES} />
    </PageHeaderStyleContext>
  )
}
