import { createContext, useContext } from 'react'

/**
 * @typedef {object} AssistantContextValue
 * @property {boolean} available The signed-in user has an assistant (the launcher is shown).
 * @property {boolean} answering The assistant is writing an answer (the launchers show a pulsing dot).
 * @property {() => void} openAssistant Opens the assistant panel.
 */

const noop = () => undefined

export const AssistantContext = createContext({ available: false, answering: false, openAssistant: noop })

/**
 * The assistant panel of the signed-in user (customers and admins), for pages that offer a way to open it.
 * @returns {AssistantContextValue}
 */
export function useAssistant() {
  return useContext(AssistantContext)
}
