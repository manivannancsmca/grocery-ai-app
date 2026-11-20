import { Link } from 'react-router'
import { RoleAssistantCard } from '../../../features/ai/RoleAssistantCard'
import { AcceptingOrdersSwitch } from '../../../features/menu/AcceptingOrdersSwitch'
import { MenuSummaryCard } from '../../../features/menu/MenuSummaryCard'
import { RESTAURANT_NOT_FOUND } from '../../../features/restaurant/api'
import { useRestaurantProfile } from '../../../features/restaurant/hooks'
import { RestaurantOnboarding } from '../../../features/restaurant/RestaurantOnboarding'
import { StatusBadge } from '../../../features/stakeholders/StatusBadge'
import { StoreShortcutCards } from '../../../features/store-orders/StoreShortcutCards'
import { StoreDashboardCharts } from '../../../features/store-reports/StoreDashboardCharts'
import { ProfileGate } from '../../../shared/components/ProfileGate'
import { Card, DescriptionList, PageHeader } from '../../../shared/components/ui'
import { formatAddress, formatDate, humanizeToken } from '../../../shared/utils/format'

/** Until the restaurant is APPROVED the dashboard is the onboarding flow; afterwards the overview. */
export function RestaurantDashboardPage() {
  const profile = useRestaurantProfile()

  return (
    <ProfileGate query={profile} notFoundCode={RESTAURANT_NOT_FOUND}>
      {(restaurant) =>
        restaurant.status !== 'APPROVED' ? (
          <div className="w-full">
            <PageHeader
              icon="cloche"
              title="Set up your restaurant"
              description="Add your kitchen's details, cuisines and opening hours. We review every restaurant before going live."
              actions={
                <Link to="/restaurant/menu" className="text-sm font-semibold text-emerald-700 hover:text-emerald-800">
                  Prepare your menu →
                </Link>
              }
            />
            <RestaurantOnboarding profile={restaurant} />
          </div>
        ) : (
          <div className="space-y-6">
            <PageHeader
              icon="cloche"
              title={restaurant.name ?? 'Restaurant dashboard'}
              description="Run your menu, live order board and sales reports."
            />
            <Card
              icon="cloche"
              title="Your restaurant"
              actions={
                <Link to="/restaurant/profile" className="text-sm font-semibold text-emerald-700 hover:text-emerald-800">
                  View profile
                </Link>
              }
            >
              <DescriptionList
                items={[
                  { label: 'Status', value: <StatusBadge status={restaurant.status} /> },
                  { label: 'Live since', value: formatDate(restaurant.approvedAt) },
                  { label: 'Cuisines', value: restaurant.cuisines?.map(humanizeToken).join(', ') },
                  { label: 'Address', value: formatAddress(restaurant.address) },
                ]}
              />
              <div className="mt-5 border-t border-slate-100 pt-4">
                <AcceptingOrdersSwitch acceptingOrders={restaurant.acceptingOrders ?? true} />
              </div>
            </Card>
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              <MenuSummaryCard />
              <StoreShortcutCards kind="RESTAURANT" />
              <RoleAssistantCard
                title="AI restaurant assistant"
                description="Ask about the order queue, sales, returns and reviews. It prepares sold-out dishes and order decisions for you to confirm."
                actionsPath="/restaurant/assistant"
              />
            </div>
            <StoreDashboardCharts kind="RESTAURANT" />
          </div>
        )
      }
    </ProfileGate>
  )
}
