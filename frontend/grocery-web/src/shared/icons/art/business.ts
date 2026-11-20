import { C, circle, fill, type IconArt, line, rect } from '../shapes'

const GEAR_TOOTH = rect(10.6, 1.8, 2.8, 4.2, 0.9)

/** Running the business: reports, people, settings, system health, paperwork and offers. */
export const BUSINESS_ART = {
  /** A bar chart: reports. */
  chart: [
    fill(C.emerald500, rect(4, 12, 3.3, 8, 1)),
    fill(C.sky500, rect(8.6, 7, 3.3, 13, 1)),
    fill(C.violet500, rect(13.2, 10, 3.3, 10, 1)),
    fill(C.amber400, rect(17.8, 4, 3.3, 16, 1)),
    fill(C.slate300, rect(2.4, 19.8, 19.2, 1.7, 0.85)),
  ],
  /** A rising line: growth, sales. */
  trendUp: [
    fill(C.emerald200, 'M3 17.6 8.6 11.6l4 3.6L20.4 7.4V20H3Z'),
    line(C.emerald600, 'M3 17.6 8.6 11.6l4 3.6 6.6-6.6', 2.2),
    fill(C.emerald600, 'M21.4 5.2v5.4a.5.5 0 0 1-.85.35l-5.4-5.4a.5.5 0 0 1 .35-.85h5.4a.5.5 0 0 1 .5.5Z'),
    fill(C.slate300, rect(2.4, 19.8, 19.2, 1.7, 0.85)),
  ],
  /** Two people: customers, users. */
  users: [
    fill(C.orange300, circle(16.6, 7.6, 3.2)),
    fill(C.sky500, 'M11.4 19.6a5.2 5.2 0 0 1 10.4 0v.2a.7.7 0 0 1-.7.7h-9a.7.7 0 0 1-.7-.7Z'),
    fill(C.amber300, circle(9, 8.4, 3.9)),
    fill(C.indigo500, 'M2.4 20.2a6.6 6.6 0 0 1 13.2 0 .8.8 0 0 1-.8.8H3.2a.8.8 0 0 1-.8-.8Z'),
  ],
  /** A gear: operations and settings. */
  settings: [
    ...[0, 45, 90, 135, 180, 225, 270, 315].map((angle) => ({ ...fill(C.indigo500, GEAR_TOOTH), transform: `rotate(${angle} 12 12)` })),
    fill(C.indigo500, circle(12, 12, 7.4)),
    fill(C.indigo300, circle(12, 12, 4.6)),
    fill(C.white, circle(12, 12, 2.2)),
  ],
  /** A monitor with a heartbeat: system status. */
  pulse: [
    fill(C.slate600, rect(11, 16.6, 2, 2.4)),
    fill(C.slate500, rect(8.4, 18.6, 7.2, 2, 1)),
    fill(C.slate700, rect(2.4, 3.6, 19.2, 13.4, 2.4)),
    line(C.emerald400, 'M5 10.6h3.2l1.7-3.4 2.8 6.6 2.1-3.2H19', 1.8),
  ],
  /** A clipboard: purchase orders. */
  clipboard: [
    fill(C.amber500, rect(4.4, 3.6, 15.2, 18, 2.2)),
    fill(C.white, rect(6.3, 6.4, 11.4, 13.4, 1.2)),
    line(C.slate400, 'M8.6 10.4h6.8M8.6 13.4h6.8M8.6 16.4h4', 1.4),
    fill(C.slate600, rect(8.4, 2, 7.2, 3.8, 1.2)),
  ],
  /** A ticket with a percent sign: coupons. */
  ticket: [
    fill(C.violet500, 'M3 7.5A1.5 1.5 0 0 1 4.5 6h15A1.5 1.5 0 0 1 21 7.5V10a2 2 0 0 0 0 4v2.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 16.5V14a2 2 0 0 0 0-4Z'),
    fill(C.violet300, 'M15.4 6h4.1A1.5 1.5 0 0 1 21 7.5V10a2 2 0 0 0 0 4v2.5a1.5 1.5 0 0 1-1.5 1.5h-4.1Z'),
    { ...line(C.white, 'M15.4 7.4v9.2', 1.3), dash: '1.6 1.6' },
    line(C.white, 'm7.4 14.6 4.4-5.2', 1.5),
    fill(C.white, circle(8.1, 9.9, 1)),
    fill(C.white, circle(11.1, 14.1, 1)),
  ],
} satisfies Record<string, IconArt>
