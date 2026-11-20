import { createContext, useContext } from 'react'

/** The store area's new-order alerts (sound + flashing tab title), as the board's controls see them. */
export interface OrderAlertsValue {
  /** The browser has the Web Audio API. */
  soundSupported: boolean
  /** The "Sound alerts" switch (remembered in localStorage). */
  soundOn: boolean
  setSoundOn: (on: boolean) => void
  /** The browser allowed audio (the user interacted with the page since it loaded). */
  soundUnlocked: boolean
  /** Unlocks audio from a click ("Click to enable sound alerts"). */
  enableSound: () => void
  /** New-order alerts since the user last looked at the page (the "(n) New order" tab title). */
  unseenNewOrders: number
}

export const OrderAlertsContext = createContext<OrderAlertsValue | null>(null)

/** Null outside the store area (no alerts there). */
export function useOrderAlerts(): OrderAlertsValue | null {
  return useContext(OrderAlertsContext)
}
