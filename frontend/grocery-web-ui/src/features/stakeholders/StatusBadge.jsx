import { Badge } from '../../shared/components/ui'
import { isStakeholderStatus, STATUS_LABELS, STATUS_TONES } from './types'

/** @param {{ status: string }} props */
export function StatusBadge({ status }) {
  if (!isStakeholderStatus(status)) return <Badge>{status}</Badge>
  return (
    <Badge tone={STATUS_TONES[status]} dot>
      {STATUS_LABELS[status]}
    </Badge>
  )
}
