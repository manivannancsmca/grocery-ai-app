import { C, circle, fill, type IconArt, line, rect } from '../shapes'

const PIN = 'M12 2a7.5 7.5 0 0 0-7.5 7.5c0 5.4 6.2 11.3 6.9 12a.9.9 0 0 0 1.2 0c.7-.7 6.9-6.6 6.9-12A7.5 7.5 0 0 0 12 2Z'
const EYE = 'M12 5c5.2 0 8.8 4.4 9.8 6.3a1.4 1.4 0 0 1 0 1.4C20.8 14.6 17.2 19 12 19S3.2 14.6 2.2 12.7a1.4 1.4 0 0 1 0-1.4C3.2 9.4 6.8 5 12 5Z'
const STAR = 'M12 2.6l2.78 5.63 6.2.9-4.49 4.38 1.06 6.18L12 16.77l-5.55 2.92 1.06-6.18-4.49-4.38 6.2-.9Z'
const SPARKLE_BIG = 'M10 4.5c.7 4.3 2.7 6.3 7 7-4.3.7-6.3 2.7-7 7-.7-4.3-2.7-6.3-7-7 4.3-.7 6.3-2.7 7-7Z'

/** Everyday pictures: home, people, messages, time, places, tools and the assistant's sparkle. */
export const EVERYDAY_ART = {
  home: [
    fill(C.red600, rect(15.6, 4.2, 2.4, 5, 0.5)),
    fill(C.amber200, rect(5, 10, 14, 11, 1.2)),
    fill(C.red500, 'M11.1 3.4a1.4 1.4 0 0 1 1.8 0l8.5 7.2a.8.8 0 0 1-.52 1.4H3.12a.8.8 0 0 1-.52-1.4Z'),
    fill(C.emerald500, rect(10, 14.4, 4, 6.6, 1)),
    fill(C.sky400, rect(6.4, 13.4, 2.6, 2.6, 0.5)),
    fill(C.sky400, rect(15, 13.4, 2.6, 2.6, 0.5)),
  ],
  user: [
    fill(C.indigo500, 'M3.8 20.2a8.2 8.2 0 0 1 16.4 0 .8.8 0 0 1-.8.8H4.6a.8.8 0 0 1-.8-.8Z'),
    fill(C.amber300, circle(12, 7.8, 4.3)),
    fill(C.amber400, 'M15.9 9.6a4.3 4.3 0 0 1-7.8 0 5 5 0 0 0 7.8 0Z'),
  ],
  mail: [
    fill(C.sky500, rect(2.5, 4.5, 19, 15, 2.2)),
    fill(C.sky200, 'M3 5.6A2.2 2.2 0 0 1 4.7 4.5h14.6A2.2 2.2 0 0 1 21 5.6l-7.6 6.7a2.2 2.2 0 0 1-2.8 0Z'),
  ],
  /** A handset: calling someone. */
  phone: [
    fill(C.emerald500, 'M6.5 3.2h2.8a1 1 0 0 1 .94.66l1.3 3.6a1 1 0 0 1-.34 1.13L9.3 9.85a11 11 0 0 0 4.85 4.85l1.26-1.9a1 1 0 0 1 1.13-.34l3.6 1.3a1 1 0 0 1 .66.94v2.8a2 2 0 0 1-2.1 2A16.6 16.6 0 0 1 4.5 5.3a2 2 0 0 1 2-2.1Z'),
    line(C.emerald400, 'M14.6 3.6a5.9 5.9 0 0 1 5.8 5.8', 1.6),
    line(C.emerald400, 'M14.6 6.9a2.6 2.6 0 0 1 2.5 2.5', 1.6),
  ],
  /** A mobile phone: a phone number field. */
  mobile: [
    fill(C.slate700, rect(6.5, 2.5, 11, 19, 2.3)),
    fill(C.sky300, rect(8, 4.8, 8, 12.6, 0.8)),
    fill(C.white, 'M8 9.6 12.8 4.8H15L8 11.8Z', 0.45),
    fill(C.slate400, rect(10.5, 18.7, 3, 1.2, 0.6)),
  ],
  lock: [
    line(C.slate400, 'M8 10.5V7.8a4 4 0 0 1 8 0v2.7', 2.3),
    fill(C.amber400, rect(4.5, 10, 15, 11.5, 2.5)),
    fill(C.amber500, 'M13 10h4a2.5 2.5 0 0 1 2.5 2.5V19a2.5 2.5 0 0 1-2.5 2.5h-4Z'),
    fill(C.amber800, circle(12, 14.6, 1.7)),
    fill(C.amber800, rect(11.25, 15, 1.5, 3.4, 0.75)),
  ],
  /** A shield with a tick: safe, approved, nothing happens without you. */
  shield: [
    fill(C.emerald500, 'M12 2.4 4 5.5v5.7c0 4.9 3.4 9 8 10.4 4.6-1.4 8-5.5 8-10.4V5.5Z'),
    fill(C.emerald600, 'M12 2.4v19.2c4.6-1.4 8-5.5 8-10.4V5.5Z'),
    line(C.white, 'm8.6 12 2.4 2.4 4.6-4.8', 2.1),
  ],
  calendar: [
    fill(C.blue200, rect(3, 5, 18, 16, 2.5)),
    fill(C.red500, 'M3 7.5A2.5 2.5 0 0 1 5.5 5h13A2.5 2.5 0 0 1 21 7.5V10H3Z'),
    fill(C.slate700, rect(7, 2.6, 2, 4.6, 1)),
    fill(C.slate700, rect(15, 2.6, 2, 4.6, 1)),
    fill(C.blue500, rect(6, 12.4, 3, 2.7, 0.6)),
    fill(C.blue500, rect(10.5, 12.4, 3, 2.7, 0.6)),
    fill(C.amber500, rect(15, 12.4, 3, 2.7, 0.6)),
    fill(C.blue500, rect(6, 16.4, 3, 2.7, 0.6)),
    fill(C.blue500, rect(10.5, 16.4, 3, 2.7, 0.6)),
  ],
  clock: [
    fill(C.sky500, circle(12, 12, 9.6)),
    fill(C.white, circle(12, 12, 7.3)),
    line(C.slate700, 'M12 7.6V12l2.9 1.9', 1.9),
    fill(C.red500, circle(12, 12, 1.2)),
  ],
  bell: [
    fill(C.amber600, circle(12, 3, 1.3)),
    fill(C.amber700, 'M9.7 18.2a2.3 2.3 0 0 0 4.6 0Z'),
    fill(C.amber400, 'M12 3.4a6 6 0 0 0-6 6v4l-1.65 2.7a1 1 0 0 0 .85 1.5h13.6a1 1 0 0 0 .85-1.5L18 13.4v-4a6 6 0 0 0-6-6Z'),
    fill(C.amber500, 'M3.9 17.2h16.2a1 1 0 0 1 0 2H3.9a1 1 0 0 1 0-2Z'),
    line(C.white, 'M8.9 9.2a3.4 3.4 0 0 1 1.9-2.6', 1.4, 0.7),
  ],
  /** A speech bubble: chats, replies, reported problems. */
  chat: [
    fill(C.violet500, 'M5 3.8h14a2.5 2.5 0 0 1 2.5 2.5v8.2A2.5 2.5 0 0 1 19 17h-7.2l-4.6 3.7a.6.6 0 0 1-1-.47V17H5a2.5 2.5 0 0 1-2.5-2.5V6.3A2.5 2.5 0 0 1 5 3.8Z'),
    fill(C.white, rect(6.5, 8, 11, 1.8, 0.9)),
    fill(C.white, rect(6.5, 11.6, 7, 1.8, 0.9)),
  ],
  pencil: [
    fill(C.pink400, rect(9.5, 1.8, 5, 3.4, 1.2)),
    fill(C.slate300, rect(9.5, 4.6, 5, 1.9)),
    fill(C.amber400, rect(9.5, 6.5, 5, 10.5)),
    fill(C.amber500, rect(12, 6.5, 2.5, 10.5)),
    fill(C.amber200, 'M9.5 17h5L12 22Z'),
    fill(C.slate700, 'M11.1 20.2h1.8L12 22Z'),
  ].map((shape) => ({ ...shape, transform: 'rotate(45 12 12)' })),
  trash: [
    line(C.red600, 'M9.6 5V3.8a1 1 0 0 1 1-1h2.8a1 1 0 0 1 1 1V5', 1.6),
    fill(C.red400, 'M5.6 8.4h12.8l-1 11.1a2 2 0 0 1-2 1.8H8.6a2 2 0 0 1-2-1.8Z'),
    fill(C.red600, rect(3.8, 5, 16.4, 2.8, 1.4)),
    line(C.white, 'M10 11.6v6.2M14 11.6v6.2', 1.5, 0.85),
  ],
  list: [
    fill(C.emerald500, circle(5, 6, 1.9)),
    fill(C.amber400, circle(5, 12, 1.9)),
    fill(C.sky500, circle(5, 18, 1.9)),
    fill(C.slate400, rect(9, 4.9, 11.5, 2.2, 1.1)),
    fill(C.slate400, rect(9, 10.9, 11.5, 2.2, 1.1)),
    fill(C.slate400, rect(9, 16.9, 8, 2.2, 1.1)),
  ],
  note: [
    fill(C.amber200, rect(4.5, 3.5, 15, 17.5, 2)),
    fill(C.amber500, 'M6.5 3.5h11a2 2 0 0 1 2 2V7.4h-15V5.5a2 2 0 0 1 2-2Z'),
    line(C.amber700, 'M8 11h8M8 14.2h8M8 17.4h5', 1.5),
  ],
  briefcase: [
    line(C.amber900, 'M9 6.5V5a1.5 1.5 0 0 1 1.5-1.5h3A1.5 1.5 0 0 1 15 5v1.5', 1.8),
    fill(C.amber700, rect(2.5, 6.5, 19, 14, 2.5)),
    fill(C.amber600, 'M2.5 9A2.5 2.5 0 0 1 5 6.5h14A2.5 2.5 0 0 1 21.5 9v3.6h-19Z'),
    fill(C.amber300, rect(10.4, 11.2, 3.2, 3, 0.7)),
  ],
  search: [
    line(C.amber500, 'm15.8 15.8 4.8 4.8', 3.4),
    fill(C.sky500, circle(10.5, 10.5, 7.3)),
    fill(C.sky200, circle(10.5, 10.5, 5.1)),
    line(C.white, 'M7.7 9.6a3.1 3.1 0 0 1 2.1-2.2', 1.5),
  ],
  eye: [
    fill(C.sky200, EYE),
    fill(C.blue500, circle(12, 12, 4)),
    fill(C.slate800, circle(12, 12, 1.8)),
    fill(C.white, circle(13.4, 10.6, 0.8)),
  ],
  eyeOff: [
    fill(C.slate200, EYE),
    fill(C.slate400, circle(12, 12, 4)),
    fill(C.slate700, circle(12, 12, 1.8)),
    line(C.white, 'M4 4l16 16', 4),
    line(C.red500, 'M4 4l16 16', 2),
  ],
  speaker: [
    fill(C.amber500, 'M3.5 9.2a1 1 0 0 1 1-1H8l4.6-3.9a.8.8 0 0 1 1.3.6v14.2a.8.8 0 0 1-1.3.6L8 15.8H4.5a1 1 0 0 1-1-1Z'),
    fill(C.amber600, 'M3.5 12h10.4v7.1a.8.8 0 0 1-1.3.6L8 15.8H4.5a1 1 0 0 1-1-1Z'),
    line(C.sky500, 'M16.6 9a4.2 4.2 0 0 1 0 6', 2),
    line(C.sky400, 'M19.1 6.5a7.8 7.8 0 0 1 0 11', 2),
  ],
  globe: [
    fill(C.sky400, circle(12, 12, 9.5)),
    fill(C.emerald500, 'M6 7.2c1.5-.5 3.6-.3 4.4 1 .7 1.1-.2 2.3-1.2 2.9-1 .6-1.2 1.8-.6 2.8.6 1.1.3 2.6-.9 3.1-1.1.4-2-.7-2.5-1.9C4.3 13.2 4 11.6 4.2 10c.2-1.2.8-2.4 1.8-2.8Z'),
    fill(C.emerald500, 'M14.6 12.8c1.3-.5 3.1-.2 3.9.9.8 1.2.2 2.9-.9 3.9-1.1 1-2.5 1.3-3.2.4-.6-.8-.3-2-.6-3-.2-.9 0-1.9.8-2.2Z'),
    fill(C.emerald500, 'M13.5 4.2c1.5-.3 3.2.3 4.2 1.4-.6.9-1.8 1.3-2.9 1-1-.3-1.6-1.4-1.3-2.4Z'),
  ],
  /** Find my location. */
  crosshair: [
    line(C.sky600, 'M12 2v3.4M12 18.6V22M2 12h3.4M18.6 12H22', 2.2),
    fill(C.sky200, circle(12, 12, 7.2)),
    fill(C.sky500, circle(12, 12, 4.3)),
    fill(C.white, circle(12, 12, 1.7)),
  ],
  mapPin: [fill(C.red500, PIN), fill(C.red600, 'M12 2v19.75a.9.9 0 0 0 .6-.25c.7-.7 6.9-6.6 6.9-12A7.5 7.5 0 0 0 12 2Z'), fill(C.white, circle(12, 9.5, 2.8))],
  route: [
    { ...line(C.slate400, 'M8.6 18.5H15a3.5 3.5 0 0 0 0-7H9a3.5 3.5 0 0 1 0-7h5.6', 1.7), dash: '2.2 2.2' },
    fill(C.emerald500, circle(6, 18.5, 2.7)),
    fill(C.white, circle(6, 18.5, 1)),
    fill(C.red500, 'M18 1.4a3.3 3.3 0 0 0-3.3 3.3c0 2.4 2.6 4.8 3 5.1a.45.45 0 0 0 .6 0c.4-.3 3-2.7 3-5.1A3.3 3.3 0 0 0 18 1.4Z'),
    fill(C.white, circle(18, 4.7, 1.15)),
  ],
  sliders: [
    fill(C.slate300, rect(3, 6, 18, 2, 1)),
    fill(C.slate300, rect(3, 11, 18, 2, 1)),
    fill(C.slate300, rect(3, 16, 18, 2, 1)),
    fill(C.emerald500, circle(8, 7, 2.7)),
    fill(C.amber400, circle(15.6, 12, 2.7)),
    fill(C.sky500, circle(10.2, 17, 2.7)),
  ],
  filter: [
    fill(C.sky400, 'M3.6 4.6A1 1 0 0 1 4.4 3h15.2a1 1 0 0 1 .8 1.6L14.4 12.2v6.3a1 1 0 0 1-.55.9l-3 1.5a.7.7 0 0 1-1.02-.63V12.2Z'),
    fill(C.sky700, 'M9.6 12.2h4.8v6.3a1 1 0 0 1-.55.9l-3 1.5a.7.7 0 0 1-1.02-.63Z'),
  ],
  /** Four coloured tiles: every category. */
  grid: [
    fill(C.emerald500, rect(3.3, 3.3, 7.7, 7.7, 2)),
    fill(C.amber400, rect(13, 3.3, 7.7, 7.7, 2)),
    fill(C.sky500, rect(3.3, 13, 7.7, 7.7, 2)),
    fill(C.pink400, rect(13, 13, 7.7, 7.7, 2)),
  ],
  star: [fill(C.amber400, STAR), fill(C.amber500, 'M12 2.6v14.17l5.55 2.92-1.06-6.18 4.49-4.38-6.2-.9Z')],
  /** The assistant's mark. */
  sparkle: [
    fill(C.amber400, SPARKLE_BIG),
    fill(C.amber500, 'M10 4.5c.7 4.3 2.7 6.3 7 7-4.3.7-6.3 2.7-7 7Z'),
    fill(C.violet500, 'M18.2 2.2c.33 1.9 1.3 2.87 3.2 3.2-1.9.33-2.87 1.3-3.2 3.2-.33-1.9-1.3-2.87-3.2-3.2 1.9-.33 2.87-1.3 3.2-3.2Z'),
    fill(C.pink400, circle(18.6, 18.4, 1.7)),
  ],
  refresh: [
    line(C.emerald500, 'M4.8 11.2a7.3 7.3 0 0 1 12.6-4.6', 2.6),
    fill(C.emerald500, 'M20.2 3.6v5.8a.6.6 0 0 1-.6.6h-5.8a.6.6 0 0 1-.42-1.02l5.8-5.8a.6.6 0 0 1 1.02.42Z'),
    line(C.sky500, 'M19.2 12.8a7.3 7.3 0 0 1-12.6 4.6', 2.6),
    fill(C.sky500, 'M3.8 20.4v-5.8a.6.6 0 0 1 .6-.6h5.8a.6.6 0 0 1 .42 1.02l-5.8 5.8a.6.6 0 0 1-1.02-.42Z'),
  ],
  /** A U-turn arrow: returns and refunds. */
  return: [
    line(C.violet500, 'M8.4 8.4h6.6a5.6 5.6 0 0 1 0 11.2h-5', 3),
    fill(C.violet600, 'M3.5 7.9 8.5 3.7a.65.65 0 0 1 1.07.5v8.4a.65.65 0 0 1-1.07.5l-5-4.2a.65.65 0 0 1 0-1Z'),
  ],
  /** Two arrows: handed over to someone else. */
  swap: [
    line(C.emerald500, 'M3.8 7.6h11.4', 2.6),
    fill(C.emerald500, 'M14.6 3.7a.7.7 0 0 1 1.13-.55l5 3.9a.7.7 0 0 1 0 1.1l-5 3.9a.7.7 0 0 1-1.13-.55Z'),
    line(C.sky500, 'M20.2 16.4H8.8', 2.6),
    fill(C.sky500, 'M9.4 12.5a.7.7 0 0 0-1.13-.55l-5 3.9a.7.7 0 0 0 0 1.1l5 3.9a.7.7 0 0 0 1.13-.55Z'),
  ],
  hourglass: [
    fill(C.sky200, 'M7 4.8h10v1.9a5 5 0 0 1-2.1 4.1L13.2 12l1.7 1.2a5 5 0 0 1 2.1 4.1v1.9H7v-1.9a5 5 0 0 1 2.1-4.1l1.7-1.2-1.7-1.2A5 5 0 0 1 7 6.7Z'),
    fill(C.amber400, 'M8.8 7.4h6.4a3.4 3.4 0 0 1-1.4 2.3L12 11l-1.8-1.3a3.4 3.4 0 0 1-1.4-2.3Z'),
    fill(C.amber400, 'M8.4 18.4c.3-1.7 1.6-3 3.6-3.4 2 .4 3.3 1.7 3.6 3.4Z'),
    line(C.amber400, 'M12 11v4', 0.9),
    fill(C.amber700, rect(5, 2.4, 14, 2.6, 1.3)),
    fill(C.amber700, rect(5, 19, 14, 2.6, 1.3)),
  ],
  /** A balance: the pack size. */
  scale: [
    line(C.slate400, 'M5.4 7.6 2.8 13.2M5.4 7.6 8 13.2M18.6 7.6 16 13.2M18.6 7.6 21.2 13.2', 1),
    fill(C.slate600, rect(11.1, 4, 1.8, 15.5)),
    fill(C.slate600, rect(7, 19, 10, 2.2, 1.1)),
    fill(C.slate500, rect(4, 6.6, 16, 1.8, 0.9)),
    fill(C.amber400, 'M2.3 13a3.1 3.1 0 0 0 6.2 0Z'),
    fill(C.amber400, 'M15.5 13a3.1 3.1 0 0 0 6.2 0Z'),
    fill(C.amber500, circle(12, 4.2, 1.7)),
  ],
  bolt: [
    fill(C.amber400, 'M13.6 2.2 4.7 13.3a.7.7 0 0 0 .55 1.14h5.5l-1.05 7.06c-.1.68.75 1.03 1.17.5l8.4-10.9a.7.7 0 0 0-.55-1.13h-5.36l1.4-7.25c.13-.68-.73-1.06-1.16-.52Z'),
    fill(C.amber500, 'M12.4 10h5.82a.7.7 0 0 1 .55 1.13l-8.4 10.9c-.42.53-1.27.18-1.17-.5l1.05-7.06Z'),
  ],
  leaf: [
    fill(C.emerald500, 'M4.6 19.5C4 11.4 9 5 20.2 3.8c-.4 10.6-6.4 15.8-13.7 15.8-.7 0-1.3 0-1.9-.1Z'),
    fill(C.emerald400, 'M4.6 19.5C4 11.4 9 5 20.2 3.8Z'),
    line(C.white, 'M4.6 19.5 14.6 9.4', 1.4),
  ],
} satisfies Record<string, IconArt>
