import { Link } from 'react-router'
import { RESTAURANT_NOT_FOUND } from '../../../features/menu/api'
import { useMyMenu } from '../../../features/menu/hooks'
import { MenuEditor } from '../../../features/menu/MenuEditor'
import { MenuOverview } from '../../../features/menu/MenuOverview'
import { MenuSkeleton } from '../../../features/menu/MenuSkeleton'
import { ApiError, hasErrorCode } from '../../../shared/api/problemDetail'
import { Alert, Button, ErrorState, PageHeader } from '../../../shared/components/ui'

/**
 * `/restaurant/menu` — the owner's menu editor: overview tiles (with the accepting-orders switch), then the categories
 * and dishes. The menu can be prepared before approval; it is read-only while the restaurant is suspended.
 */
export function MenuPage() {
  const menu = useMyMenu()

  return (
    <div className="space-y-5">
      <PageHeader
        icon="menuBook"
        title="Menu"
        description="Categories, dishes, prices and availability. Once you are live, changes reach customers within seconds."
      />

      {menu.isPending ? (
        <MenuSkeleton
          message={
            hasErrorCode(menu.failureReason, RESTAURANT_NOT_FOUND)
              ? 'Setting up your restaurant — one moment…'
              : 'Loading your menu…'
          }
        />
      ) : menu.isError ? (
        hasErrorCode(menu.error, RESTAURANT_NOT_FOUND) ? (
          <Alert
            tone="warning"
            title="Set up your restaurant first"
            actions={
              <Button variant="secondary" size="sm" onClick={() => void menu.refetch()}>
                Check again
              </Button>
            }
          >
            Your restaurant profile is created right after registration. If this message stays, open your{' '}
            <Link to="/restaurant/profile" className="font-semibold underline">
              restaurant profile
            </Link>
            .
          </Alert>
        ) : (
          <ErrorState
            title="Could not load your menu"
            message={menu.error.message}
            correlationId={menu.error instanceof ApiError ? menu.error.correlationId : null}
            onRetry={() => void menu.refetch()}
          />
        )
      ) : (
        <>
          {menu.data.restaurantStatus === 'SUSPENDED' ? (
            <Alert tone="danger" role="status" title="Your restaurant is suspended">
              The menu is read-only until our team reinstates your restaurant. Contact support to resolve it.
            </Alert>
          ) : menu.data.restaurantStatus !== 'APPROVED' ? (
            <Alert
              tone="info"
              role="status"
              title="Your menu goes live when your restaurant is approved"
              actions={
                <Link to="/restaurant/profile" className="text-sm font-semibold text-sky-900 underline">
                  Check your onboarding status
                </Link>
              }
            >
              Prepare it now — customers see it as soon as the approval comes through.
            </Alert>
          ) : null}

          <MenuOverview menu={menu.data} readOnly={menu.data.restaurantStatus === 'SUSPENDED'} />
          <MenuEditor menu={menu.data} readOnly={menu.data.restaurantStatus === 'SUSPENDED'} />
        </>
      )}
    </div>
  )
}
