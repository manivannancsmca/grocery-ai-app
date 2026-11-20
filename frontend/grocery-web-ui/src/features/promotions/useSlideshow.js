import { useCallback, useEffect, useState, useSyncExternalStore } from 'react'
import { useMediaQuery } from '../../shared/hooks/useMediaQuery'

/** @typedef {import('react').FocusEvent} FocusEvent */

/**
 * @typedef {object} Slideshow
 * @property {number} count
 * @property {number} index The slide shown now.
 * @property {ReadonlySet<number>} ready Slides whose photos may load: every slide shown so far and the one after the current.
 * @property {boolean} playing The visitor's choice (the play / pause button). Off by default with reduced motion.
 * @property {boolean} running Moving right now: playing, and not held by a pointer, keyboard focus or a hidden browser tab.
 * @property {(index: number) => void} go
 * @property {() => void} next
 * @property {() => void} previous
 * @property {() => void} togglePlaying
 * @property {{ onFocus: (event: FocusEvent<HTMLElement>) => void, onBlur: (event: FocusEvent<HTMLElement>) => void }} focusHold Spread on the element whose keyboard focus holds the slideshow (typing in the hero's search box).
 * @property {{ onPointerEnter: () => void, onPointerLeave: () => void }} pointerHold Spread on the slides themselves: a pointer resting on them holds the slideshow.
 */

/**
 * Whether the browser tab is in the background (no point turning slides nobody sees).
 * @returns {boolean}
 */
function useDocumentHidden() {
  return useSyncExternalStore(
    (onChange) => {
      document.addEventListener('visibilitychange', onChange)
      return () => document.removeEventListener('visibilitychange', onChange)
    },
    () => document.hidden,
    () => false,
  )
}

/**
 * Keyboard focus, or a text field (typing in the search box): those hold the slideshow. A mouse click on one of its
 * buttons does not (the button keeps focus, which would otherwise stop the slides for good after one click).
 * @param {EventTarget} target
 * @returns {boolean}
 */
function holdsFocus(target) {
  if (!(target instanceof Element)) return false
  try {
    return target.matches(':focus-visible')
  } catch {
    return true // a browser without :focus-visible: any focus holds
  }
}

/**
 * The slide after {@code index} and the one after that, wrapping round.
 * @param {number} index
 * @param {number} count
 */
const withNext = (index, count) => [index, (index + 1) % count]

/**
 * An automatic slideshow of {@code count} slides that turns every {@code intervalMs}. It follows the WAI-ARIA carousel
 * pattern's rules for motion: it holds while the pointer rests on the slides or keyboard focus is inside the hero,
 * while the tab is hidden, and it starts paused when the visitor prefers reduced motion (and without `matchMedia`, as
 * in tests). Choosing a slide restarts its full time.
 * @param {number} count
 * @param {number} intervalMs
 * @returns {Slideshow}
 */
export function useSlideshow(count, intervalMs) {
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)', true)
  const hidden = useDocumentHidden()
  const [index, setIndex] = useState(0)
  const [ready, setReady] = useState(() => new Set(withNext(0, count)))
  // null until the visitor presses play or pause: until then the motion preference decides.
  const [choice, setChoice] = useState(null)
  const [pointerOn, setPointerOn] = useState(false)
  const [focusIn, setFocusIn] = useState(false)

  const playing = choice ?? !reducedMotion
  const running = playing && !pointerOn && !focusIn && !hidden && count > 1

  const go = useCallback(
    (target) => {
      const slide = ((target % count) + count) % count
      setIndex(slide)
      setReady((loaded) =>
        withNext(slide, count).every((i) => loaded.has(i)) ? loaded : new Set([...loaded, ...withNext(slide, count)]),
      )
    },
    [count],
  )

  // One countdown per slide shown: a new slide (by hand or by the timer) or a resume starts the full time again.
  useEffect(() => {
    if (!running) return undefined
    const timer = window.setTimeout(() => go(index + 1), intervalMs)
    return () => window.clearTimeout(timer)
  }, [running, index, intervalMs, go])

  return {
    count,
    index,
    ready,
    playing,
    running,
    go,
    next: () => go(index + 1),
    previous: () => go(index - 1),
    togglePlaying: () => setChoice(!playing),
    focusHold: {
      onFocus: (event) => setFocusIn(holdsFocus(event.target)),
      onBlur: (event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setFocusIn(false)
      },
    },
    pointerHold: { onPointerEnter: () => setPointerOn(true), onPointerLeave: () => setPointerOn(false) },
  }
}
