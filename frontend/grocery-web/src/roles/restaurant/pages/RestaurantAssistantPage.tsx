import { RoleAssistantPage } from '../../../features/ai/RoleAssistantPage'

/** `/restaurant/assistant` (Phase 12 §6): what the restaurant assistant prepared — dish availability, accepting or rejecting orders. */
export function RestaurantAssistantPage() {
  return (
    <RoleAssistantPage
      agentId="restaurant"
      title="Assistant actions"
      description="The restaurant assistant never changes your menu or orders on its own: you confirm each action, and only then does it run."
      emptyDescription="When the assistant prepares to mark a dish sold out or to accept or reject an order, you confirm it here or in the chat."
    />
  )
}
