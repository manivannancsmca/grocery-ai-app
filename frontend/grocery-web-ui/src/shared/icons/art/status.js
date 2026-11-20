import { C, circle, fill, line, rect } from '../shapes'

/** Round and triangular status marks, in the colour of what they say (info, done, failed, careful, stopped). */
export const STATUS_ART = {
  info: [
    fill(C.sky500, circle(12, 12, 9.5)),
    fill(C.white, circle(12, 7.9, 1.35)),
    fill(C.white, rect(10.9, 10.4, 2.2, 6.8, 1.1)),
  ],
  checkCircle: [fill(C.emerald500, circle(12, 12, 9.5)), line(C.white, 'm7.8 12.3 2.8 2.8 5.6-5.8', 2.3)],
  xCircle: [fill(C.red500, circle(12, 12, 9.5)), line(C.white, 'm8.8 8.8 6.4 6.4m0-6.4-6.4 6.4', 2.3)],
  alertCircle: [
    fill(C.red500, circle(12, 12, 9.5)),
    fill(C.white, rect(10.9, 6.6, 2.2, 7.4, 1.1)),
    fill(C.white, circle(12, 16.8, 1.35)),
  ],
  warning: [
    fill(C.amber400, 'M10.27 3.9a2 2 0 0 1 3.46 0l8.06 13.98A2 2 0 0 1 20.06 21H3.94a2 2 0 0 1-1.73-3.12Z'),
    fill(C.slate800, rect(10.95, 8.4, 2.1, 6.6, 1.05)),
    fill(C.slate800, circle(12, 17.6, 1.25)),
  ],
  /** "No entry": rejected, refused, blocked. */
  ban: [fill(C.red500, circle(12, 12, 9.5)), fill(C.white, rect(6.4, 10.6, 11.2, 2.8, 1.4))],
  /** On hold: e.g. a restaurant that is not taking orders. */
  pauseCircle: [
    fill(C.amber500, circle(12, 12, 9.5)),
    fill(C.white, rect(8.6, 7.4, 2.4, 9.2, 1.2)),
    fill(C.white, rect(13, 7.4, 2.4, 9.2, 1.2)),
  ],
}
