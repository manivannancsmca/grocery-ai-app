import { createContext, useContext } from 'react'

/**
 * The store area's new-order alerts (sound + flashing tab title), as the board's controls see them.
 * @typedef {object} OrderAlertsValue
 * @property {boolean} soundSupported The browser has the Web Audio API.
 * @property {boolean} soundOn The "Sound alerts" switch (remembered in localStorage).
 * @property {(on: boolean) => void} setSoundOn
 * @property {boolean} soundUnlocked The browser allowed audio (the user interacted with the page since it loaded).
 * @property {() => void} enableSound Unlocks audio from a click ("Click to enable sound alerts").
 * @property {number} unseenNewOrders New-order alerts since the user last looked at the page (the "(n) New order" tab title).
 */

export const OrderAlertsContext = createContext(null)

/**
 * Null outside the store area (no alerts there).
 * @returns {OrderAlertsValue | null}
 */
export function useOrderAlerts() {
  return useContext(OrderAlertsContext)
}
