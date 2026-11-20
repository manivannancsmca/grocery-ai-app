import { circle, fill, line } from '../shapes'

const INK = 'currentColor'

/**
 * Control glyphs (arrows, chevrons, close, menu, plus, ticks): heavy solid shapes in the surrounding text colour, so
 * they stay legible on any button — white on a primary button, emerald on a link, red on a delete action.
 */
export const GLYPH_ART = {
  arrowRight: [
    line(INK, 'M4.5 12h10', 2.5),
    { d: 'M13.2 6.4a.9.9 0 0 1 1.45-.7l6.1 5.4a1.2 1.2 0 0 1 0 1.8l-6.1 5.4a.9.9 0 0 1-1.45-.7Z', fill: INK },
  ],
  arrowLeft: [
    line(INK, 'M19.5 12h-10', 2.5),
    { d: 'M10.8 6.4a.9.9 0 0 0-1.45-.7l-6.1 5.4a1.2 1.2 0 0 0 0 1.8l6.1 5.4a.9.9 0 0 0 1.45-.7Z', fill: INK },
  ],
  chevronRight: [line(INK, 'm9 5.5 6.5 6.5L9 18.5', 2.7)],
  plus: [line(INK, 'M12 4.5v15M4.5 12h15', 2.7)],
  check: [line(INK, 'm4.5 12.5 5 5L19.5 7', 2.7)],
  checkAll: [line(INK, 'm1.8 12.5 5 5L16.3 8', 2.4), line(INK, 'm11.8 16.5 1 1 9.4-9.5', 2.4)],
  close: [line(INK, 'M6 6l12 12M18 6 6 18', 2.7)],
  menu: [line(INK, 'M4 6.5h16M4 12h16M4 17.5h16', 2.5)],
  // The left menu's collapse / expand toggle: a window with its side panel and an arrow pointing where it goes.
  sidebarCollapse: [
    fill(INK, 'M6 4.5h3.5v15H6A2.5 2.5 0 0 1 3.5 17V7A2.5 2.5 0 0 1 6 4.5Z', 0.35),
    line(
      INK,
      'M6 4.5h12A2.5 2.5 0 0 1 20.5 7v10a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 17V7A2.5 2.5 0 0 1 6 4.5ZM9.5 4.5v15',
      2,
    ),
    line(INK, 'm16 9-3 3 3 3', 2.3),
  ],
  sidebarExpand: [
    fill(INK, 'M6 4.5h3.5v15H6A2.5 2.5 0 0 1 3.5 17V7A2.5 2.5 0 0 1 6 4.5Z', 0.35),
    line(
      INK,
      'M6 4.5h12A2.5 2.5 0 0 1 20.5 7v10a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 17V7A2.5 2.5 0 0 1 6 4.5ZM9.5 4.5v15',
      2,
    ),
    line(INK, 'm13 9 3 3-3 3', 2.3),
  ],
  // Voice: speak a question; stop recording or reading aloud. (Hearing an answer uses the `speaker` icon.)
  mic: [
    { d: 'M12 2.8a3.4 3.4 0 0 0-3.4 3.4v5.6a3.4 3.4 0 0 0 6.8 0V6.2A3.4 3.4 0 0 0 12 2.8Z', fill: INK },
    line(INK, 'M6.2 11.4a5.8 5.8 0 0 0 11.6 0M12 17.2v3.6M8.8 20.8h6.4', 2.2),
  ],
  stop: [
    fill(INK, 'M7.5 6h9A1.5 1.5 0 0 1 18 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 6 16.5v-9A1.5 1.5 0 0 1 7.5 6Z'),
  ],
  // Slideshows: play (a rounded triangle) and pause (two rounded bars).
  play: [fill(INK, 'M8 6.1a1.1 1.1 0 0 1 1.66-.95l9.1 5.5a1.6 1.6 0 0 1 0 2.7l-9.1 5.5A1.1 1.1 0 0 1 8 17.9Z')],
  pause: [
    fill(INK, 'M8 5.5h1.5A1.5 1.5 0 0 1 11 7v10a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 17V7A1.5 1.5 0 0 1 8 5.5Z'),
    fill(
      INK,
      'M14.5 5.5H16A1.5 1.5 0 0 1 17.5 7v10a1.5 1.5 0 0 1-1.5 1.5h-1.5A1.5 1.5 0 0 1 13 17V7a1.5 1.5 0 0 1 1.5-1.5Z',
    ),
  ],
  // Tables: a row's action menu, and the sort state of a column.
  more: [fill(INK, circle(5.5, 12, 2)), fill(INK, circle(12, 12, 2)), fill(INK, circle(18.5, 12, 2))],
  sortBoth: [fill(INK, 'M12 4.5 16.5 10h-9Z', 0.55), fill(INK, 'M12 19.5 7.5 14h9Z', 0.55)],
  sortUp: [fill(INK, 'M12 4.5 16.5 10h-9Z'), fill(INK, 'M12 19.5 7.5 14h9Z', 0.3)],
  sortDown: [fill(INK, 'M12 4.5 16.5 10h-9Z', 0.3), fill(INK, 'M12 19.5 7.5 14h9Z')],
  // Exports: an arrow into a tray (download), a sheet with a grid (spreadsheet), a page with lines (document).
  download: [
    line(INK, 'M12 3.5v11', 2.5),
    line(INK, 'm7.5 10.5 4.5 4.5 4.5-4.5', 2.5),
    line(INK, 'M4.5 15.5v2a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2v-2', 2.3),
  ],
  spreadsheet: [
    fill(INK, 'M6 4h12a2 2 0 0 1 2 2v3.5H4V6a2 2 0 0 1 2-2Z', 0.35),
    line(
      INK,
      'M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2ZM4 9.5h16M4 14.75h16M10 9.5V20',
      1.9,
    ),
  ],
  document: [
    fill(INK, 'M14 3.5v4a1.5 1.5 0 0 0 1.5 1.5h4', 0.35),
    line(
      INK,
      'M14 3.5H7.5a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2V8Zm0 0v4a1.5 1.5 0 0 0 1.5 1.5h3M9 13h6M9 16.5h4',
      1.9,
    ),
  ],
}
