import { createContext, useContext } from 'react'

export interface AssistantContextValue {
  /** The signed-in user has an assistant (the launcher is shown). */
  available: boolean
  /** The assistant is writing an answer (the launchers show a pulsing dot). */
  answering: boolean
  /** Opens the assistant panel. */
  openAssistant: () => void
}

const noop = () => undefined

export const AssistantContext = createContext<AssistantContextValue>({ available: false, answering: false, openAssistant: noop })

/** The assistant panel of the signed-in user (customers and admins), for pages that offer a way to open it. */
export function useAssistant(): AssistantContextValue {
  return useContext(AssistantContext)
}
