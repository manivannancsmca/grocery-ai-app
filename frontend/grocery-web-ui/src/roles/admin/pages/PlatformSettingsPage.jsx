import { SETTINGS_AREA_DEFINITIONS } from '../../../features/platform-settings/fields'
import { SettingsAreaCard } from '../../../features/platform-settings/SettingsAreaCard'
import { useAuth } from '../../../shared/auth/useAuth'
import { Alert, PageHeader } from '../../../shared/components/ui'

/**
 * `/admin/settings` — the business rules admins may change without a release (docs/api/platform-settings.md): one card
 * per area, each saved on its own by the service that owns it. Every admin may look; SUPER_ADMIN and OPS_ADMIN may
 * change them.
 */
export function PlatformSettingsPage() {
  const { can } = useAuth()
  const canManage = can('settings.manage')
  return (
    <div>
      <PageHeader
        icon="sliders"
        title="Platform settings"
        description="Business rules you can change without a release. Each service uses a change within about 30 seconds; what is already under way keeps its values."
      />
      {!canManage && (
        <Alert tone="info" role="status" title="View only" className="mb-5">
          Only super admins and operations admins can change platform settings.
        </Alert>
      )}
      <div className="grid min-w-0 items-start gap-5 xl:grid-cols-2">
        {SETTINGS_AREA_DEFINITIONS.map((definition) => (
          <SettingsAreaCard key={definition.area} definition={definition} canManage={canManage} />
        ))}
      </div>
    </div>
  )
}
