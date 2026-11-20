import { cn } from '../../shared/utils/cn'

/**
 * The auth screens' taller inputs: a soft grey fill that turns white on hover and focus. Invalid fields keep the
 * shared red fill (`controlState`), so the fill is only added without an error.
 * @param {boolean} invalid
 * @param {string} [extra]
 * @returns {string}
 */
export function authInputClass(invalid, extra) {
  return cn('h-12! pl-11! text-[0.9375rem]!', !invalid && 'bg-slate-50/80! hover:bg-white! focus:bg-white!', extra)
}
