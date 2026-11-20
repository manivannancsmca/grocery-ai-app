import { RoleAssistantPage } from '../../../features/ai/RoleAssistantPage'

/** `/vendor/assistant` (Phase 12 §6): what the vendor assistant prepared — stock, prices, purchase orders. */
export function VendorAssistantPage() {
  return (
    <RoleAssistantPage
      agentId="vendor"
      title="Assistant actions"
      description="The vendor assistant never changes stock, prices or purchase orders on its own: you confirm each action, and only then does it run."
      emptyDescription="When the assistant prepares a stock change, a new price or a purchase order, you confirm it here or in the chat."
    />
  )
}
