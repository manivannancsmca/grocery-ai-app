/** Shared look of text inputs, selects, textareas and the date / time inputs. */
export const controlBase =
  'block w-full rounded-xl border bg-white text-sm text-slate-900 shadow-xs transition-[border-color,box-shadow,background-color] duration-200 ' +
  'placeholder:text-slate-400 focus:outline-none focus:ring-4 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-500'

export function controlState(invalid: boolean | undefined): string {
  return invalid
    ? 'border-red-400 bg-red-50/30 focus:border-red-500 focus:ring-red-500/15'
    : 'border-slate-300 hover:border-slate-400 focus:border-emerald-500 focus:ring-emerald-500/15'
}
