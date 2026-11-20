import { Link } from 'react-router'
import { RoleAssistantCard } from '../../../features/ai/RoleAssistantCard'
import { useVendorProfile } from '../../../features/vendor/hooks'
import { VendorOnboarding } from '../../../features/vendor/VendorOnboarding'
import { VENDOR_NOT_FOUND } from '../../../features/vendor/api'
import { StatusBadge } from '../../../features/stakeholders/StatusBadge'
import { StoreShortcutCards } from '../../../features/store-orders/StoreShortcutCards'
import { StoreDashboardCharts } from '../../../features/store-reports/StoreDashboardCharts'
import { InventorySummaryCard } from '../../../features/vendor-inventory/InventorySummaryCard'
import { ProductsSummaryCard } from '../../../features/vendor-products/ProductsSummaryCard'
import { PricingSummaryCard } from '../../../features/vendor-pricing/PricingSummaryCard'
import { ProfileGate } from '../../../shared/components/ProfileGate'
import { Card, DescriptionList, PageHeader } from '../../../shared/components/ui'
import { formatAddress, formatDate } from '../../../shared/utils/format'

/** Until the vendor is APPROVED the dashboard is the onboarding flow; afterwards the business overview. */
export function VendorDashboardPage() {
  const profile = useVendorProfile()

  return (
    <ProfileGate query={profile} notFoundCode={VENDOR_NOT_FOUND}>
      {(vendor) =>
        vendor.status !== 'APPROVED' ? (
          <div className="w-full">
            <PageHeader icon="store" title="Set up your store" description="Tell us about your business. We review every vendor before going live." />
            <VendorOnboarding profile={vendor} />
          </div>
        ) : (
          <div className="space-y-6">
            <PageHeader
              icon="store"
              title={vendor.businessName ?? 'Vendor dashboard'}
              description="Manage your products, stock, prices and incoming grocery orders."
            />
            <Card
              icon="store"
              title="Your business"
              actions={
                <Link to="/vendor/profile" className="text-sm font-semibold text-emerald-700 hover:text-emerald-800">
                  View profile
                </Link>
              }
            >
              <DescriptionList
                items={[
                  { label: 'Status', value: <StatusBadge status={vendor.status} /> },
                  { label: 'Live since', value: formatDate(vendor.approvedAt) },
                  { label: 'GSTIN', value: vendor.gstin },
                  { label: 'Store address', value: formatAddress(vendor.address) },
                ]}
              />
            </Card>
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              <ProductsSummaryCard />
              <InventorySummaryCard />
              <PricingSummaryCard />
              <StoreShortcutCards kind="VENDOR" />
              <RoleAssistantCard
                title="AI vendor assistant"
                description="Ask about low stock, sales, returns and reviews. It drafts purchase orders and prepares stock and price changes for you to confirm."
                actionsPath="/vendor/assistant"
              />
            </div>
            <StoreDashboardCharts kind="VENDOR" />
          </div>
        )
      }
    </ProfileGate>
  )
}
