import { C, circle, fill, line, rect } from '../shapes'

/** @typedef {import('../shapes').IconArt} IconArt */

/**
 * Two dark tyres with light hubs.
 * @param {number} r
 * @param {...[ number, number ]} centres
 * @returns {IconArt}
 */
function wheels(r, ...centres) {
  return centres.flatMap(([cx, cy]) => [
    fill(C.slate700, circle(cx, cy, r)),
    fill(C.slate300, circle(cx, cy, r * 0.38)),
  ])
}

/** Shopping, stores, deliveries, money and the kitchen. */
export const COMMERCE_ART = {
  /** A shopping bag with the FreshCart leaf. */
  bag: [
    line(C.amber700, 'M8.5 9.2V6.8a3.5 3.5 0 0 1 7 0v2.4', 1.8),
    fill(
      C.amber400,
      'M5.2 7.5h13.6a1 1 0 0 1 1 .92l.9 11.2a1.8 1.8 0 0 1-1.8 1.9H5.1a1.8 1.8 0 0 1-1.8-1.9l.9-11.2a1 1 0 0 1 1-.92Z',
    ),
    fill(C.amber500, 'M12 7.5h6.8a1 1 0 0 1 1 .92l.9 11.2a1.8 1.8 0 0 1-1.8 1.9H12Z'),
    fill(C.emerald500, 'M9.6 17.6c0-2.6 1.7-4 4.9-4-.4 2.9-1.8 4.2-4 4.2-.3 0-.6 0-.9-.2Z'),
  ],
  cart: [
    fill(C.red500, circle(10.6, 6.4, 2.4)),
    fill(C.orange400, circle(15.4, 6, 2.6)),
    line(C.slate700, 'M2.5 3.5h2.2a1 1 0 0 1 .97.76L8.2 15.6h10.2', 1.9),
    fill(C.emerald500, 'M5.6 6.8h14.7a.8.8 0 0 1 .78 1l-1.6 6.3a1.6 1.6 0 0 1-1.55 1.2H8.1Z'),
    line(C.white, 'M10.6 9.4v3.3M14.1 9.4v3.3M17.6 9.4v3.3', 1.3, 0.75),
    ...wheels(1.9, [9.6, 19.4], [17, 19.4]),
  ],
  /** A cardboard box: an order, a parcel, stock. */
  box: [
    fill(C.amber300, 'M12 2.5 20.6 7 12 11.5 3.4 7Z'),
    fill(C.amber500, 'M3.4 7 12 11.5v10L3.4 17Z'),
    fill(C.amber600, 'M20.6 7 12 11.5v10l8.6-4.5Z'),
    fill(C.amber100, 'M7.2 4.9 8.9 4l8.6 4.5-1.7.9Z'),
    fill(C.amber100, 'M15.8 9.4l1.7-.9v3.4l-1.7.9Z'),
  ],
  /** A shop front with a striped awning. */
  store: [
    fill(C.amber200, rect(4.2, 9.5, 15.6, 11.5, 1)),
    fill(C.emerald600, 'M4.6 2.8h14.8l1.8 3.7H2.8Z'),
    fill(
      C.emerald500,
      'M2.8 6.5h18.4v2.6a2.3 2.3 0 0 1-4.6 0 2.3 2.3 0 0 1-4.6 0 2.3 2.3 0 0 1-4.6 0 2.3 2.3 0 0 1-4.6 0Z',
    ),
    fill(C.emerald200, 'M7.4 6.5H12v2.6a2.3 2.3 0 0 1-4.6 0Z'),
    fill(C.emerald200, 'M16.6 6.5h4.6v2.6a2.3 2.3 0 0 1-4.6 0Z'),
    fill(C.sky500, rect(10, 14.5, 4, 6.5, 0.8)),
    fill(C.sky300, rect(5.9, 13.4, 3, 3, 0.6)),
    fill(C.sky300, rect(15.1, 13.4, 3, 3, 0.6)),
  ],
  truck: [
    fill(C.emerald500, rect(1.8, 5.2, 12.8, 10.8, 1.5)),
    fill(C.white, 'M5.6 13c0-2.4 1.5-3.6 4.4-3.6-.4 2.6-1.6 3.8-3.6 3.8l-.8-.2Z', 0.9),
    fill(C.amber400, 'M14.6 8.4h3.9a1.5 1.5 0 0 1 1.2.6l2 2.7a1.5 1.5 0 0 1 .3.9V15a1 1 0 0 1-1 1h-6.4Z'),
    fill(C.sky200, 'M16.1 10h2.3l1.5 2.1h-3.8Z'),
    ...wheels(2.4, [6.4, 17.5], [17.6, 17.5]),
  ],
  /** A delivery scooter with an insulated box. */
  scooter: [
    fill(C.emerald500, rect(2.6, 6.4, 6.8, 6, 1.1)),
    fill(C.emerald600, rect(2.6, 8.5, 6.8, 1.3)),
    line(C.orange600, 'M16.2 5.6 18.6 17.8', 2.2),
    line(C.slate700, 'M14.4 5.4h3.6', 2),
    fill(C.orange500, 'M3 16.6a4.6 4.6 0 0 1 4.6-4.6h6.4l4.4 4.6Z'),
    fill(C.slate700, rect(7.6, 10, 5.6, 2.1, 1.05)),
    ...wheels(2.6, [6.4, 18.4], [18.6, 18.4]),
  ],
  bicycle: [
    fill(C.slate700, circle(5.8, 16, 4.1)),
    fill(C.sky100, circle(5.8, 16, 2.4)),
    fill(C.slate700, circle(18.2, 16, 4.1)),
    fill(C.sky100, circle(18.2, 16, 2.4)),
    line(C.sky500, 'M5.8 16 9.8 9h5.8l2.6 7M9.8 9l4 7H5.8', 2),
    line(C.slate700, 'M8 6.9h3.4', 2),
    line(C.slate700, 'M15.6 9 14.9 6.2h2.6', 1.8),
  ],
  motorcycle: [
    line(C.slate600, 'M15.6 6.6 18.6 16.6', 2),
    ...wheels(3.4, [5.4, 16.6], [18.6, 16.6]),
    fill(C.red500, 'M6.4 13.2c1.6-2.2 3.8-3.4 6.6-3.4h2.6l2 3.8-3.2 3H9.2Z'),
    fill(C.slate700, rect(5.4, 10.6, 5.8, 2, 1)),
    line(C.slate700, 'M14.2 6.6h3', 2),
    fill(C.yellow300, circle(18.5, 10, 1.3)),
  ],
  car: [
    fill(C.slate700, rect(4.4, 16.4, 3.6, 3.8, 1.1)),
    fill(C.slate700, rect(16, 16.4, 3.6, 3.8, 1.1)),
    fill(
      C.blue500,
      'M3 13.6l1.6-4.4A2.5 2.5 0 0 1 7 7.5h10a2.5 2.5 0 0 1 2.4 1.7l1.6 4.4v3.6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1Z',
    ),
    fill(C.sky200, 'M6.1 11.7l1-2.5a1 1 0 0 1 .93-.6h7.94a1 1 0 0 1 .93.6l1 2.5Z'),
    fill(C.yellow300, circle(6.2, 14.7, 1.15)),
    fill(C.yellow300, circle(17.8, 14.7, 1.15)),
  ],
  /** A price tag: prices, offers and coupons. */
  tag: [
    fill(
      C.rose500,
      'M3 4.2A1.2 1.2 0 0 1 4.2 3h7.3a1.2 1.2 0 0 1 .85.35l8.4 8.4a1.2 1.2 0 0 1 0 1.7l-7.3 7.3a1.2 1.2 0 0 1-1.7 0l-8.4-8.4A1.2 1.2 0 0 1 3 11.5Z',
    ),
    fill(C.white, circle(7.6, 7.6, 1.8)),
    line(C.white, 'm10.6 15.2 4.6-4.6', 1.4),
    fill(C.white, circle(11.3, 11.4, 0.95)),
    fill(C.white, circle(14.5, 14.6, 0.95)),
  ],
  /** A bank card. */
  card: [
    fill(C.indigo500, rect(2, 5, 20, 14, 2.5)),
    fill(C.indigo900, rect(2, 8.3, 20, 2.8)),
    fill(C.amber300, rect(4.6, 13, 4, 3, 0.7)),
    fill(C.indigo300, rect(11, 14.1, 7, 1.5, 0.75)),
  ],
  receipt: [
    fill(C.blue200, 'M5 2.8h14v18.4l-2.33-1.4-2.33 1.4-2.34-1.4-2.33 1.4-2.33-1.4L5 21.2Z'),
    line(C.blue600, 'M8.5 7.4h7M8.5 10.9h7M8.5 14.4h4', 1.6),
    fill(C.emerald500, circle(15.6, 14.4, 1.3)),
  ],
  /** A rupee coin: savings. */
  savings: [
    fill(C.amber400, circle(12, 12, 9.6)),
    fill(C.amber300, circle(12, 12, 7.3)),
    line(C.amber700, 'M9 7.6h6M9 10.2h6M9.8 7.6h1.7a2.6 2.6 0 0 1 0 5.2H9.6l4.6 4.2', 1.7),
  ],
  /** A serving dome: a restaurant, a meal ready to go. */
  cloche: [
    fill(C.amber600, circle(12, 7.6, 1.7)),
    fill(C.amber400, 'M3.8 17.2a8.2 8.2 0 0 1 16.4 0Z'),
    fill(C.amber500, 'M12 9a8.2 8.2 0 0 1 8.2 8.2H12Z'),
    line(C.white, 'M7 14.6a5.6 5.6 0 0 1 2.9-3.5', 1.5, 0.75),
    fill(C.slate500, rect(2.4, 16.9, 19.2, 2.6, 1.3)),
  ],
  /** Fork and knife. */
  cutlery: [
    fill(
      C.slate400,
      'M4.4 3.2a.7.7 0 0 1 1.4 0V7.4h.95V3.2a.7.7 0 0 1 1.4 0v4.2h.95V3.2a.7.7 0 0 1 1.4 0V8.2a3.2 3.2 0 0 1-2.3 3.07V14H6.7v-2.73A3.2 3.2 0 0 1 4.4 8.2Z',
    ),
    fill(C.orange500, rect(6.3, 12.6, 2.4, 9, 1.2)),
    fill(C.slate400, 'M18.6 2.6c-2.5 1.2-4 4.3-4 8.7v1.6a.9.9 0 0 0 .9.9h3.1Z'),
    fill(C.orange500, rect(16, 12.6, 2.6, 9, 1.3)),
  ],
  /** A pot on the boil: the kitchen is preparing the order. */
  cooking: [
    line(C.slate400, 'M8.6 2.6c-.8 1 .8 2 0 3.4M12 2.6c-.8 1 .8 2 0 3.4M15.4 2.6c-.8 1 .8 2 0 3.4', 1.4),
    fill(C.red700, rect(1.8, 11.4, 3.6, 2.2, 1.1)),
    fill(C.red700, rect(18.6, 11.4, 3.6, 2.2, 1.1)),
    fill(C.red500, 'M4.5 10h15v7a3.5 3.5 0 0 1-3.5 3.5H8A3.5 3.5 0 0 1 4.5 17Z'),
    fill(C.red400, rect(4.5, 12.8, 15, 1.4)),
    fill(C.red700, rect(10.4, 6.4, 3.2, 2, 0.8)),
    fill(C.red600, rect(3.8, 7.9, 16.4, 2.6, 1.3)),
  ],
  /** An open menu card. */
  menuBook: [
    fill(C.orange500, rect(2.3, 4.6, 19.4, 15.6, 2)),
    fill(C.amber100, 'M4 5.4c2.6-.6 5.4-.4 7.3 1v12.4c-1.9-1.3-4.7-1.5-7.3-1Z'),
    fill(C.amber100, 'M20 5.4c-2.6-.6-5.4-.4-7.3 1v12.4c1.9-1.3 4.7-1.5 7.3-1Z'),
    line(C.orange400, 'M5.8 9.2h3.6M5.8 12.2h3.6M14.6 9.2h3.6M14.6 12.2h3.6', 1.3),
  ],
}
