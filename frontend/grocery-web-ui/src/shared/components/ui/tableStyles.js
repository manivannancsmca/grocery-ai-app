/**
 * The header of every data table: a brand-colour band with white column names (white on emerald-700 / teal-700 is
 * about 5.5:1, enough for the small caps). Printed pages drop backgrounds, so the names print dark on white.
 */
export const TABLE_HEAD_CLASS = 'bg-gradient-to-r from-emerald-700 to-teal-700 print:bg-none'

/** The header row (`<tr>` inside the `<thead>`); cells that need it add `text-right`. */
export const TABLE_HEAD_ROW_CLASS =
  'text-left text-[11px] font-semibold tracking-wider whitespace-nowrap text-white uppercase print:text-slate-700'
