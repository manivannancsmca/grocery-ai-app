import { C, circle, ellipse, fill, line, rect } from '../shapes'

/** @typedef {import('../shapes').IconArt} IconArt */

/**
 * A bowl seen from the side, with a little foot.
 * @param {string} color
 * @param {string} foot
 * @returns {IconArt}
 */
function bowl(color, foot) {
  return [
    fill(foot, rect(9, 19.4, 6, 1.9, 0.9)),
    fill(color, 'M3.2 12h17.6a.6.6 0 0 1 .6.66A9.45 9.45 0 0 1 12 21a9.45 9.45 0 0 1-9.4-8.34.6.6 0 0 1 .6-.66Z'),
  ]
}

const STEAM = 'M8.8 2.8c-.8 1 .8 2 0 3.4M12 2.8c-.8 1 .8 2 0 3.4M15.2 2.8c-.8 1 .8 2 0 3.4'

/** Grocery categories (by what is in them) and restaurant cuisines. */
export const FOOD_ART = {
  /* Grocery categories */

  apple: [
    line(C.amber800, 'M12 7.4c0-1.6.4-3 1.3-4.2', 1.5),
    fill(
      C.red500,
      'M12 7.2c-1.4-1-3.6-1.4-5.3-.2-2.3 1.6-2.6 5-1.3 8.2 1.1 2.8 3 5.3 4.8 5.3.7 0 1.1-.4 1.8-.4s1.1.4 1.8.4c1.8 0 3.7-2.5 4.8-5.3 1.3-3.2 1-6.6-1.3-8.2-1.7-1.2-3.9-.8-5.3.2Z',
    ),
    line(C.white, 'M7.4 10.6c.2-1.1.8-2 1.7-2.5', 1.4, 0.6),
    fill(C.green500, 'M13.2 5.6c.9-1.7 2.6-2.5 4.6-2.1-.6 1.9-2.4 3-4.6 2.1Z'),
  ],
  milk: [
    fill(C.sky600, rect(8, 2.4, 8, 2.8, 0.6)),
    fill(C.sky400, 'M5.5 9.6 8 5h8l2.5 4.6V20a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 20Z'),
    line(C.sky600, 'M5.5 9.6h13', 1.3),
    fill(C.white, rect(7.5, 12, 9, 6.8, 1)),
    fill(C.sky500, 'M12 13.1c1 1.2 1.6 2 1.6 2.8a1.6 1.6 0 0 1-3.2 0c0-.8.6-1.6 1.6-2.8Z'),
  ],
  bread: [
    fill(
      C.amber600,
      'M4.5 11.5A3.5 3.5 0 0 1 5.6 4.9C7.5 4 9.7 3.6 12 3.6s4.5.4 6.4 1.3a3.5 3.5 0 0 1 1.1 6.6V19a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2Z',
    ),
    fill(
      C.amber200,
      'M6.5 12.3a2 2 0 0 1 .3-3.9c1.6-.8 3.3-1.1 5.2-1.1s3.6.3 5.2 1.1a2 2 0 0 1 .3 3.9v6.2a.5.5 0 0 1-.5.5h-10a.5.5 0 0 1-.5-.5Z',
    ),
  ],
  wheat: [
    line(C.amber700, 'M12 22V7.5', 1.6),
    fill(C.amber400, 'M12 2.4c1.3 1.4 1.3 3.8 0 5.2-1.3-1.4-1.3-3.8 0-5.2Z'),
    fill(C.amber500, 'M12 11.8c-2.2.2-3.9-1.2-4.2-3.3 2.2-.2 3.9 1.2 4.2 3.3Z'),
    fill(C.amber400, 'M12 11.8c2.2.2 3.9-1.2 4.2-3.3-2.2-.2-3.9 1.2-4.2 3.3Z'),
    fill(C.amber400, 'M12 16.2c-2.2.2-3.9-1.2-4.2-3.3 2.2-.2 3.9 1.2 4.2 3.3Z'),
    fill(C.amber500, 'M12 16.2c2.2.2 3.9-1.2 4.2-3.3-2.2-.2-3.9 1.2-4.2 3.3Z'),
    fill(C.green500, 'M12 21.2c-2.5-.2-4-1.6-4.2-3.3 2.2 0 3.8 1.2 4.2 3.3Z'),
  ],
  /** A bowl of lentils. */
  lentils: [
    fill(C.orange400, circle(8.4, 10.2, 1.9)),
    fill(C.amber400, circle(12, 8.8, 1.9)),
    fill(C.red400, circle(15.6, 10.2, 1.9)),
    fill(C.amber500, circle(10.2, 11.3, 1.6)),
    fill(C.orange500, circle(13.8, 11.4, 1.6)),
    ...bowl(C.teal500, C.teal600),
  ],
  /** A flour sack. */
  flourSack: [
    fill(C.amber300, 'M8 7.5 7 3.5c1.6.6 3.3.8 5 .8s3.4-.2 5-.8l-1 4Z'),
    fill(C.amber200, 'M7.5 7.5c-1.8 2.4-2.8 6-2.8 9.5 0 2.4 1.3 4.4 3.3 4.4h8c2 0 3.3-2 3.3-4.4 0-3.5-1-7.1-2.8-9.5Z'),
    fill(C.amber700, rect(7.3, 6.8, 9.4, 1.9, 0.95)),
    line(C.amber700, 'M12 18.6v-6', 1.2),
    fill(C.amber600, 'M12 14.6c-1.3.1-2.2-.7-2.4-2 1.3-.1 2.2.7 2.4 2Z'),
    fill(C.amber600, 'M12 14.6c1.3.1 2.2-.7 2.4-2-1.3-.1-2.2.7-2.4 2Z'),
  ],
  cookie: [
    fill(C.amber500, circle(12, 12, 9.4)),
    fill(C.amber400, circle(11.2, 11.2, 7.6)),
    fill(C.amber900, circle(8.6, 8.6, 1.3)),
    fill(C.amber900, circle(14.6, 7.8, 1.2)),
    fill(C.amber900, circle(15.6, 13.8, 1.4)),
    fill(C.amber900, circle(9.2, 14.8, 1.3)),
    fill(C.amber900, circle(12.3, 11.4, 1.1)),
  ],
  /** A glass of juice with a straw. */
  juice: [
    line(C.red500, 'M13.4 9.4 15.4 2.6h2.6', 1.7),
    fill(C.sky200, 'M5.5 7.4h13l-1.4 12.4a2 2 0 0 1-2 1.7H8.9a2 2 0 0 1-2-1.7Z'),
    fill(C.orange400, 'M6.4 11h11.2l-1 8.6a1.2 1.2 0 0 1-1.2 1.1H8.6a1.2 1.2 0 0 1-1.2-1.1Z'),
    line(C.white, 'M8.3 12.6l.6 5.6', 1.2, 0.6),
  ],
  fish: [
    fill(C.sky600, 'M5.2 12 1.8 8.2v7.6Z'),
    fill(C.sky600, 'M9.6 7.2c1-1.7 2.7-2.5 4.6-2.4l-.5 2.4Z'),
    fill(C.sky400, 'M3.2 12c2.2-3.4 5.4-5.4 9-5.4s6.5 2 8.6 5.4c-2.1 3.4-5 5.4-8.6 5.4S5.4 15.4 3.2 12Z'),
    line(C.sky600, 'M13 9.2c.9 1.7.9 3.9 0 5.6', 1.2),
    fill(C.white, circle(16.6, 11.2, 1.25)),
    fill(C.slate800, circle(16.8, 11.2, 0.6)),
  ],
  egg: [
    fill(C.orange300, 'M12 2.8c3.6 0 6.5 5.4 6.5 10.2a6.5 6.5 0 0 1-13 0C5.5 8.2 8.4 2.8 12 2.8Z'),
    fill(C.orange400, 'M12 2.8c3.6 0 6.5 5.4 6.5 10.2A6.5 6.5 0 0 1 12 19.5Z', 0.55),
    line(C.white, 'M8.6 11.4c.2-1.6.8-3 1.6-4', 1.4, 0.75),
  ],
  /** A chilli: spices and masalas. */
  chilli: [
    line(C.green600, 'M17.6 6.2c.3-1.6 1.2-2.8 2.6-3.4', 1.6),
    fill(
      C.red500,
      'M19.6 7.3c1 2.1.1 5-1.9 7.9-2.7 3.8-6.9 6.4-11.5 6.3-1 0-1.2-1-.4-1.5 3.1-1.8 5.4-4.4 6.8-7.4 1.2-2.6 2.5-5 4.6-5.8 1-.4 1.9-.2 2.4.5Z',
    ),
    fill(C.green500, 'M14.8 7.6c.6-1.6 2.6-2.4 4.2-1.6 1 .5 1.1 1.4.6 1.9-1.2 1.1-3.6.9-4.8-.3Z'),
    line(C.white, 'M14.4 11.2c-.7 1.6-1.7 3-3 4.2', 1.2, 0.55),
  ],
  /** A bottle of oil. */
  oilBottle: [
    fill(C.green600, rect(10, 1.8, 4, 2.8, 0.6)),
    fill(
      C.yellow400,
      'M10.4 4.6h3.2v2.2l2.7 3.4a2 2 0 0 1 .4 1.2V20a1.5 1.5 0 0 1-1.5 1.5H8.8A1.5 1.5 0 0 1 7.3 20v-8.6a2 2 0 0 1 .4-1.2l2.7-3.4Z',
    ),
    fill(C.emerald500, rect(7.3, 12.6, 9.4, 5.4)),
    fill(C.yellow300, 'M12 13.6c.9 1 1.4 1.8 1.4 2.5a1.4 1.4 0 0 1-2.8 0c0-.7.5-1.5 1.4-2.5Z'),
  ],
  iceCream: [
    fill(C.amber500, 'M7.2 11.2h9.6l-4 10.2a.85.85 0 0 1-1.6 0Z'),
    line(C.amber700, 'M9 13.4l4.4 3.8M15 13.4l-4.4 3.8', 1),
    fill(C.pink300, 'M6.4 11.2a5.6 5.6 0 1 1 11.2 0 1.4 1.4 0 0 1-1.4 1.4H7.8a1.4 1.4 0 0 1-1.4-1.4Z'),
    line(C.sky400, 'M9.3 8.8l.9-.5', 1.1),
    line(C.yellow400, 'M13.7 8.2l.9.5', 1.1),
    line(C.emerald400, 'M11.4 10.4l.7.5', 1.1),
    line(C.green600, 'M12 3.6c.3-.9 1-1.5 1.8-1.7', 1),
    fill(C.red500, circle(12, 5, 1.5)),
  ],
  /** A spray bottle: household and cleaning. */
  sprayBottle: [
    fill(C.sky300, circle(19, 8.2, 1.25)),
    fill(C.sky300, circle(20.6, 11.4, 0.85)),
    fill(C.sky300, circle(18.8, 4.6, 0.9)),
    fill(C.blue600, rect(5.4, 3.8, 3.6, 1.8, 0.6)),
    line(C.blue600, 'M9.6 6.6 8.3 9.8', 1.5),
    fill(C.blue400, rect(10.2, 6.2, 3.6, 2.8)),
    fill(C.blue600, rect(8.4, 2.8, 7.2, 3.8, 1)),
    fill(C.sky400, 'M9 8.8h6a2 2 0 0 1 2 2V20a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 20v-9.2a2 2 0 0 1 2-2Z'),
    fill(C.white, rect(8.6, 13, 6.8, 4.6, 0.8)),
  ],
  /** A pump bottle: personal care. */
  pumpBottle: [
    fill(C.slate500, rect(7, 3, 4.4, 1.5, 0.75)),
    fill(C.slate500, rect(10.3, 3, 3.4, 4, 0.6)),
    fill(C.pink500, rect(9, 6.6, 6, 2.2, 0.5)),
    fill(C.pink400, 'M8 8.6h8a2 2 0 0 1 2 2V20a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 6 20v-9.4a2 2 0 0 1 2-2Z'),
    fill(C.white, 'M12 11.8c1.4 1.6 2.2 2.8 2.2 3.8a2.2 2.2 0 0 1-4.4 0c0-1 .8-2.2 2.2-3.8Z'),
  ],
  basket: [
    line(C.amber700, 'M5.6 10a6.4 6.4 0 0 1 12.8 0', 1.7),
    fill(C.red500, circle(9.2, 8.6, 2.6)),
    fill(C.green500, 'M12.8 9.6c-.6-3 1-5.6 4.4-6.3.4 3.4-1.4 5.6-4.4 6.3Z'),
    fill(
      C.amber500,
      'M2.8 10.4h18.4a.8.8 0 0 1 .78 1l-1.9 8.2a2 2 0 0 1-1.95 1.6H5.9a2 2 0 0 1-1.95-1.6L2 11.4a.8.8 0 0 1 .8-1Z',
    ),
    line(C.amber700, 'M8 13.4v4.8M12 13.4v4.8M16 13.4v4.8', 1.3),
    fill(C.amber600, rect(2, 9.6, 20, 2.4, 1.2)),
  ],

  /* Restaurant cuisines */

  curry: [
    line(C.slate400, STEAM, 1.4),
    fill(C.amber400, ellipse(12, 12, 8.6, 1.9)),
    ...bowl(C.orange500, C.orange600),
    fill(C.green500, 'M13.2 11.4c.5-1.2 1.6-1.8 3-1.6-.5 1.2-1.6 1.8-3 1.6Z'),
  ],
  noodles: [
    line(C.amber700, 'M12.6 2.6 9.6 11.4M19.4 4 13.2 11.6', 1.4),
    fill(C.amber200, ellipse(12, 12, 8.6, 2)),
    line(C.amber400, 'M6.4 11.8c1-1 2 1 3 0s2 1 3 0 2 1 3 0 1.4.6 2.2.2', 1),
    ...bowl(C.red500, C.red600),
    line(C.white, 'M7.4 15.4h9.2', 1.2, 0.6),
  ],
  thali: [
    fill(C.slate400, circle(12, 12, 10)),
    fill(C.slate100, circle(12, 12, 8.3)),
    fill(C.orange400, circle(8.3, 8.9, 2.6)),
    fill(C.green500, circle(15.3, 8.4, 2.3)),
    fill(C.red400, circle(15.9, 14.9, 2.4)),
    fill(C.amber300, circle(9, 15, 3)),
    fill(C.amber500, circle(8.2, 14.4, 0.5)),
    fill(C.amber500, circle(9.9, 15.8, 0.5)),
  ],
  pizza: [
    fill(C.yellow300, 'M12 21.6 3 6.8C5.6 4.6 8.7 3.4 12 3.4s6.4 1.2 9 3.4Z'),
    fill(C.amber500, 'M3 6.8C5.6 4.6 8.7 3.4 12 3.4s6.4 1.2 9 3.4l-1.3 2.2C17.5 7.3 14.9 6.3 12 6.3S6.5 7.3 4.3 9Z'),
    fill(C.red500, circle(9.8, 10.6, 1.5)),
    fill(C.red500, circle(14.4, 11.6, 1.4)),
    fill(C.red500, circle(12, 15.6, 1.2)),
  ],
  burger: [
    fill(C.amber500, 'M4 10.4C4.5 6.6 7.8 4 12 4s7.5 2.6 8 6.4a.8.8 0 0 1-.8.9H4.8a.8.8 0 0 1-.8-.9Z'),
    line(C.amber100, 'M8.8 7.4l.8-.3M12.4 6.4l.8.3M15.2 7.8l.7.4', 1),
    line(
      C.green500,
      'M3.6 12.5c1.3 0 1.3.9 2.7.9s1.3-.9 2.7-.9 1.3.9 2.8.9 1.3-.9 2.7-.9 1.3.9 2.7.9 1.3-.9 2.6-.9',
      1.8,
    ),
    fill(C.amber800, rect(4, 14.2, 16, 2.8, 1.4)),
    fill(C.yellow400, 'M4.8 13.8h14.4l-2.2 2-2-1H4.8Z'),
    fill(C.amber500, 'M4.4 18h15.2v.6a2.4 2.4 0 0 1-2.4 2.4H6.8a2.4 2.4 0 0 1-2.4-2.4Z'),
  ],
  coffee: [
    line(C.slate400, 'M8.2 2.8c-.8 1 .8 2 0 3.4M11.4 2.8c-.8 1 .8 2 0 3.4', 1.4),
    fill(C.orange300, rect(3, 19.4, 16, 2, 1)),
    line(C.orange600, 'M16.4 10.2h1.3a2.5 2.5 0 0 1 0 5h-1.3', 1.9),
    fill(C.orange500, 'M5 8.6h11.5v4.9a5.5 5.5 0 0 1-5.5 5.5h-.5A5.5 5.5 0 0 1 5 13.5Z'),
    fill(C.amber900, ellipse(10.75, 8.6, 5.75, 1.2)),
  ],
  cupcake: [
    fill(C.pink500, 'M6 12.5h12l-1.6 8.1a1 1 0 0 1-1 .9H8.6a1 1 0 0 1-1-.9Z'),
    line(C.pink300, 'M10 12.8l.4 8.4M14 12.8l-.4 8.4', 1.2),
    fill(C.pink300, 'M5.4 12.6A3 3 0 0 1 6.7 7a4.2 4.2 0 0 1 5.3-3 4.2 4.2 0 0 1 5.3 3 3 3 0 0 1 1.3 5.6Z'),
    fill(C.pink200, 'M8 8.4a2.6 2.6 0 0 1 3-2.2c-.4.9-1.6 1.8-3 2.2Z'),
    fill(C.red500, circle(12, 3.6, 1.6)),
  ],
  salad: [
    fill(C.lime500, 'M12 12.5c-.8-4 1.4-7.6 6-8.5.4 4.6-2.2 7.8-6 8.5Z'),
    fill(C.green500, 'M9.4 12.5C7 11.2 6.2 8.7 7.1 6.5c2.3 1 3.2 3.5 2.3 6Z'),
    fill(C.red500, circle(14.4, 11, 1.8)),
    ...bowl(C.emerald500, C.emerald600),
  ],
}
