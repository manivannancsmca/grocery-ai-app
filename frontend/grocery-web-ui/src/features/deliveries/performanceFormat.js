/** Formats shared by the delivery partner's performance card. */

import { formatLongDay, formatShortDay } from '../../shared/utils/indiaDate'

/**
 * `16 Nov – 18 Nov`, or one day written out.
 * @param {string} from
 * @param {string} to
 * @returns {string}
 */
export function rangeText(from, to) {
  return from === to ? formatLongDay(from) : `${formatShortDay(from)} – ${formatShortDay(to)}`
}
