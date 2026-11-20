import { BADGED_ART } from './art/badged'
import { BUSINESS_ART } from './art/business'
import { COMMERCE_ART } from './art/commerce'
import { EVERYDAY_ART } from './art/everyday'
import { FOOD_ART } from './art/food'
import { GLYPH_ART } from './art/glyphs'
import { STATUS_ART } from './art/status'
import type { IconArt } from './shapes'

/** The icon sets. They never share a name (a unit test checks), so they merge into one registry. */
export const ICON_SETS = { STATUS_ART, EVERYDAY_ART, COMMERCE_ART, BUSINESS_ART, FOOD_ART, BADGED_ART, GLYPH_ART } as const

export const ICON_ART = {
  ...STATUS_ART,
  ...EVERYDAY_ART,
  ...COMMERCE_ART,
  ...BUSINESS_ART,
  ...FOOD_ART,
  ...BADGED_ART,
  ...GLYPH_ART,
} satisfies Record<string, IconArt>

export type IconName = keyof typeof ICON_ART
