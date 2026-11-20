import { useCallback, useEffect, useEffectEvent, useMemo, useState } from 'react'
import { useLiveNotifications } from '../../shared/realtime/useRealtime'
import { OrderAlertsContext } from './alertsContext'
import { createBeeper, readSoundPreference, writeSoundPreference } from './sound'

/** @typedef {import('react').ReactNode} ReactNode */

/** The tab title alternates between "(n) New order" and the page title at this pace. */
export const TITLE_FLASH_MS = 1_000

/**
 * New-order alerts for a signed-in vendor or restaurant owner, on every page of their area (Phase 8):
 * - a `NEW_ORDER` notification from the live stream plays a short beep (when "Sound alerts" is on and the
 *   browser allows audio) and flashes the tab title "(n) New order";
 * - the first click or key press on the page unlocks audio (browsers block it until then) and any
 *   interaction, or coming back to the tab, ends the flashing. The first click still does what it was aimed at.
 * Replayed, already-read and expired (past `acceptBy`) alerts make no noise.
 * @param {{ children: ReactNode }} props
 */
export function StoreOrderAlertsProvider({ children }) {
  const [beeper] = useState(createBeeper)
  const soundSupported = beeper.supported()
  const [soundOn, setSoundOnState] = useState(readSoundPreference)
  const [soundUnlocked, setSoundUnlocked] = useState(false)
  const [unseenNewOrders, setUnseenNewOrders] = useState(0)

  const enableSound = useCallback(() => {
    void beeper.unlock().then(setSoundUnlocked)
  }, [beeper])

  const setSoundOn = useCallback(
    (on) => {
      writeSoundPreference(on)
      setSoundOnState(on)
      // The switch is clicked: a user gesture, so audio can be unlocked right now.
      if (on) enableSound()
    },
    [enableSound],
  )

  const onInteraction = useEffectEvent(() => {
    setUnseenNewOrders(0)
    if (soundOn && soundSupported && !soundUnlocked) enableSound()
  })

  useEffect(() => {
    const interacted = () => onInteraction()
    const looked = () => {
      if (document.visibilityState === 'visible') setUnseenNewOrders(0)
    }
    // `click`, not `pointerdown`: unlocking audio removes the "Click to enable sound alerts" button and shifts the
    // layout. On pointerdown that happened between press and release, so the user's first click (e.g. "Accept")
    // landed elsewhere and was lost. A click has already reached its target before the re-render.
    document.addEventListener('click', interacted, true)
    document.addEventListener('keydown', interacted, true)
    document.addEventListener('visibilitychange', looked)
    window.addEventListener('focus', looked)
    return () => {
      document.removeEventListener('click', interacted, true)
      document.removeEventListener('keydown', interacted, true)
      document.removeEventListener('visibilitychange', looked)
      window.removeEventListener('focus', looked)
    }
  }, [])

  useEffect(() => () => beeper.close(), [beeper])

  useLiveNotifications((notification, { isNew }) => {
    if (!isNew || notification.read || notification.type !== 'NEW_ORDER') return
    const acceptBy =
      typeof notification.data.acceptBy === 'string' ? Date.parse(notification.data.acceptBy) : Number.NaN
    if (Number.isFinite(acceptBy) && acceptBy <= Date.now()) return
    setUnseenNewOrders((count) => count + 1)
    // Silent until the browser allowed audio (enableSound) — never forced.
    if (soundOn) beeper.beep()
  })

  // "(n) New order" ⇄ the page title, until the user looks; the cleanup always restores the page title.
  useEffect(() => {
    if (unseenNewOrders === 0) return
    const pageTitle = document.title
    const alertTitle = `(${unseenNewOrders}) New order`
    let showingAlert = true
    document.title = alertTitle
    const timer = setInterval(() => {
      showingAlert = !showingAlert
      document.title = showingAlert ? alertTitle : pageTitle
    }, TITLE_FLASH_MS)
    return () => {
      clearInterval(timer)
      document.title = pageTitle
    }
  }, [unseenNewOrders])

  const value = useMemo(
    () => ({ soundSupported, soundOn, setSoundOn, soundUnlocked, enableSound, unseenNewOrders }),
    [soundSupported, soundOn, setSoundOn, soundUnlocked, enableSound, unseenNewOrders],
  )

  return <OrderAlertsContext value={value}>{children}</OrderAlertsContext>
}
