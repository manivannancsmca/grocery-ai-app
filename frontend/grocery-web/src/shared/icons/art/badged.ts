import { withBadge } from '../badges'
import type { IconArt } from '../shapes'
import { COMMERCE_ART } from './commerce'
import { EVERYDAY_ART } from './everyday'

const { home, mapPin, sparkle, star, return: back } = EVERYDAY_ART

/** Pictures with a status mark: what became of an order, a delivery, a review, a return or an assistant action. */
export const BADGED_ART = {
  homeCheck: withBadge(home, 'check'),
  homeCross: withBadge(home, 'cross'),
  bagCheck: withBadge(COMMERCE_ART.bag, 'check'),
  pinQuestion: withBadge(mapPin, 'question'),
  sparkleQuestion: withBadge(sparkle, 'question'),
  sparklePause: withBadge(sparkle, 'pause'),
  sparkleCheck: withBadge(sparkle, 'check'),
  sparkleCross: withBadge(sparkle, 'cross'),
  starPlus: withBadge(star, 'plus'),
  starFlag: withBadge(star, 'flag'),
  starCross: withBadge(star, 'cross'),
  returnAlert: withBadge(back, 'alert'),
  returnCheck: withBadge(back, 'check'),
  returnCross: withBadge(back, 'cross'),
} satisfies Record<string, IconArt>
