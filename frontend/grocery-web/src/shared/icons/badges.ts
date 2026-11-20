import { C, circle, fill, type IconArt, line } from './shapes'

/**
 * A small round mark in the bottom-right corner that says what happened to the thing in the icon (a review that was
 * flagged, a return that was approved, an assistant action that failed).
 */
function mark(color: string, ...glyph: IconArt): IconArt {
  return [fill(C.white, circle(18.4, 18.4, 5.6)), fill(color, circle(18.4, 18.4, 4.5)), ...glyph]
}

const BADGES = {
  check: mark(C.emerald500, line(C.white, 'm16.4 18.5 1.4 1.4 2.6-2.8', 1.5)),
  cross: mark(C.red500, line(C.white, 'm16.8 16.8 3.2 3.2m0-3.2-3.2 3.2', 1.5)),
  plus: mark(C.emerald500, line(C.white, 'M18.4 16.3v4.2M16.3 18.4h4.2', 1.5)),
  question: mark(C.sky500, line(C.white, 'M17 17.2a1.5 1.5 0 1 1 2.1 1.4c-.45.2-.7.5-.7 1', 1.3), fill(C.white, circle(18.4, 21, 0.75))),
  pause: mark(C.slate500, line(C.white, 'M17.3 16.7v3.4M19.5 16.7v3.4', 1.5)),
  alert: mark(C.amber500, line(C.white, 'M18.4 16.2v2.6', 1.5), fill(C.white, circle(18.4, 20.7, 0.8))),
  flag: mark(C.rose500, { d: 'M16.9 21v-5.1h3.6l-.8 1.3.8 1.3h-3.6', fill: C.white }),
}

/** The art shrunk towards the top-left corner, with a status mark in the bottom-right one. */
export function withBadge(art: IconArt, badge: keyof typeof BADGES): IconArt {
  return [...art.map((shape) => ({ ...shape, transform: `scale(0.84)${shape.transform ? ` ${shape.transform}` : ''}` })), ...BADGES[badge]]
}
