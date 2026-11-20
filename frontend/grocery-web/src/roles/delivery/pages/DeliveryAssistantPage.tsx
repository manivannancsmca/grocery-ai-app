import { RoleAssistantPage } from '../../../features/ai/RoleAssistantPage'

/**
 * `/delivery/assistant` (Phase 12 §6): the delivery assistant's requests. Partners use it to look things up; a delivery
 * manager's reassignments wait here until approved.
 */
export function DeliveryAssistantPage() {
  return (
    <RoleAssistantPage
      agentId="delivery"
      title="Assistant actions"
      description="The delivery assistant answers questions about deliveries. Reassignments it prepares run only after approval."
      emptyDescription="Nothing waits for a decision. Ask the assistant about your active delivery, offers or history."
    />
  )
}
