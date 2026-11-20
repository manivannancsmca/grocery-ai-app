import { Badge } from '../../shared/components/ui'
import { isStakeholderStatus, STATUS_LABELS, STATUS_TONES } from './types'

export function StatusBadge({ status }: { status: string }) {
  if (!isStakeholderStatus(status)) return <Badge>{status}</Badge>
  return (
    <Badge tone={STATUS_TONES[status]} dot>
      {STATUS_LABELS[status]}
    </Badge>
  )
}
