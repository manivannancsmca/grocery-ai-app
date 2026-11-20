/**
 * The app pages an answer points to ("View order GA-…", "Open my cart") are shown as buttons, inside the answer's
 * text and in the row under it. They stay links underneath (a page to open), so "open in a new tab" still works.
 */
export const ANSWER_LINK_BUTTON =
  'group inline-flex max-w-full items-center gap-1.5 rounded-lg bg-emerald-600 font-semibold text-white no-underline shadow-sm shadow-emerald-700/25 transition ' +
  'hover:bg-emerald-700 hover:shadow-md focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 active:scale-[0.98] motion-reduce:transition-none'

/** The arrow at the end of each of those buttons. */
export const ANSWER_LINK_ARROW = 'size-3.5 shrink-0 transition-transform group-hover:translate-x-0.5 motion-reduce:transition-none'
