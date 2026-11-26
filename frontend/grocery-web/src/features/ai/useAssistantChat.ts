import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, errorMessage, hasErrorCode, problemNumber } from '../../shared/api/problemDetail'
import { cartKeys } from '../cart/api'
import {
  AI_ERRORS,
  aiKeys,
  type ApprovalView,
  type ConversationMessage,
  fetchConversation,
  type KnowledgeSource,
  type ToolActivity,
} from './api'
import { type ChatStreamEvent, streamChat, UNVERIFIED_ANSWER_MESSAGE } from './chatStream'
import { storeApproval } from './hooks'
import { CART_WRITE_TOOLS } from './labels'

/** `streaming` while the answer is written; how it ended otherwise. */
export type TurnStatus = 'streaming' | 'done' | 'error' | 'cancelled' | 'interrupted'

export interface ChatMessage {
  key: string
  role: 'USER' | 'ASSISTANT'
  content: string
  tools: ToolActivity[]
  approvals: ApprovalView[]
  status: TurnStatus
  /** A failed turn: the stream's `error` event or a refused request, in user-friendly words. */
  error: { code: string | null; message: string } | null
  /** A question asked by voice in this session (its answer is read aloud when it is complete). */
  spoken?: boolean
  /** An answer stating figures that no tool returned: shown as not checked against the app's data. */
  notice?: { code: string; message: string } | null
  /** The streamed text was dropped and the answer is being checked again (live `retry` event), until new text comes. */
  checking?: string | null
  /** The help-center documents the answer was given (shown under it once complete). */
  sources?: KnowledgeSource[]
}

export interface SendOptions {
  /** The question was spoken (the microphone filled the input). */
  voice?: boolean
}

/** A complete answer, for whoever reads answers aloud. */
export interface CompletedAnswer {
  key: string
  content: string
  /** Its question was spoken. */
  spoken: boolean
}

export interface AssistantChatOptions {
  /** Called once an answer has streamed completely (`done`). */
  onAnswerDone?: (answer: CompletedAnswer) => void
}

export interface AssistantChat {
  conversationId: string | null
  messages: ChatMessage[]
  /** An answer is being streamed. */
  streaming: boolean
  /** A stored conversation is being loaded. */
  loading: boolean
  loadError: string | null
  send: (message: string, options?: SendOptions) => void
  /** Stops the answer being written (the part received so far stays). */
  cancel: () => void
  startNew: () => void
  open: (conversationId: string) => void
}

let sequence = 0
function nextKey(prefix: string): string {
  sequence += 1
  return `${prefix}-${sequence}`
}

/** Tool events arrive per state (STARTED, then SUCCEEDED …): one chip per invocation, in first-seen order. */
export function mergeToolActivity(tools: readonly ToolActivity[], activity: ToolActivity): ToolActivity[] {
  const index = tools.findIndex((tool) => tool.invocationId === activity.invocationId)
  if (index === -1) return [...tools, activity]
  return tools.map((tool, position) => (position === index ? { ...tool, ...activity, summary: activity.summary ?? tool.summary } : tool))
}

function mergeApproval(approvals: readonly ApprovalView[], approval: ApprovalView): ApprovalView[] {
  return approvals.some((entry) => entry.id === approval.id)
    ? approvals.map((entry) => (entry.id === approval.id ? approval : entry))
    : [...approvals, approval]
}

/** A refused chat request in the assistant's own words (the rest of the app is not affected). */
export function chatErrorMessage(error: unknown): { code: string | null; message: string } {
  if (hasErrorCode(error, AI_ERRORS.rateLimited)) {
    const seconds = problemNumber(error, 'retryAfter')
    const wait = seconds !== null && seconds > 0 ? ` Try again in about ${Math.max(1, Math.ceil(seconds / 60))} min.` : ' Try again later.'
    return { code: AI_ERRORS.rateLimited, message: `You have sent the assistant a lot of messages in the last hour.${wait} Everything else works as usual.` }
  }
  if (hasErrorCode(error, AI_ERRORS.conversationNotFound)) {
    return { code: AI_ERRORS.conversationNotFound, message: 'This conversation no longer exists. Start a new one to continue.' }
  }
  if (hasErrorCode(error, AI_ERRORS.agentNotAllowed, AI_ERRORS.agentNotFound)) {
    return { code: error.errorCode, message: 'This assistant is not available for your account.' }
  }
  if (error instanceof ApiError && (error.kind === 'network' || error.kind === 'timeout' || (error.status !== null && error.status >= 500))) {
    return { code: 'AI_UNAVAILABLE', message: 'The assistant is not available right now. Everything else works as usual.' }
  }
  return { code: error instanceof ApiError ? error.errorCode : null, message: errorMessage(error) }
}

function fromHistory(message: ConversationMessage): ChatMessage {
  return {
    key: message.id,
    role: message.role === 'USER' ? 'USER' : 'ASSISTANT',
    content: message.content,
    tools: message.tools,
    approvals: message.approvals,
    status: 'done',
    error: null,
    notice: message.unverified ? { code: 'UNVERIFIED_FIGURES', message: UNVERIFIED_ANSWER_MESSAGE } : null,
    sources: message.sources ?? [],
  }
}

/**
 * The open conversation of the assistant panel: sends a message, reads the streamed answer event by event into
 * the last assistant message (tool chips, approval cards, text), and keeps the cart and the approval cards'
 * cache current. One answer at a time; switching or leaving cancels it.
 */
export function useAssistantChat(agent: string, options: AssistantChatOptions = {}): AssistantChat {
  const queryClient = useQueryClient()
  const onAnswerDoneRef = useRef(options.onAnswerDone)
  useEffect(() => {
    onAnswerDoneRef.current = options.onAnswerDone
  })
  const [conversationId, setConversationId] = useState<string | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [streaming, setStreaming] = useState(false)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const controllerRef = useRef<AbortController | null>(null)
  /** Bumped by every switch (new / open); late answers of an older one are dropped. */
  const generationRef = useRef(0)
  const conversationRef = useRef<string | null>(null)

  useEffect(
    () => () => {
      generationRef.current += 1
      controllerRef.current?.abort()
    },
    [],
  )

  const setConversation = useCallback((id: string | null) => {
    conversationRef.current = id
    setConversationId(id)
  }, [])

  const send = useCallback(
    (text: string, sendOptions: SendOptions = {}) => {
      const message = text.trim()
      if (!message || controllerRef.current) return
      const generation = generationRef.current
      const controller = new AbortController()
      controllerRef.current = controller
      const answerKey = nextKey('assistant')
      const spoken = sendOptions.voice === true
      let answerText = ''
      setStreaming(true)
      setMessages((current) => [
        ...current,
        { key: nextKey('user'), role: 'USER', content: message, tools: [], approvals: [], status: 'done', error: null, spoken },
        { key: answerKey, role: 'ASSISTANT', content: '', tools: [], approvals: [], status: 'streaming', error: null },
      ])

      const update = (change: (answer: ChatMessage) => ChatMessage) => {
        if (generation !== generationRef.current) return
        setMessages((current) => current.map((entry) => (entry.key === answerKey ? change(entry) : entry)))
      }

      const onEvent = (event: ChatStreamEvent) => {
        if (generation !== generationRef.current) return
        switch (event.type) {
          case 'conversation':
            setConversation(event.conversationId)
            return
          case 'tool':
            update((answer) => ({ ...answer, tools: mergeToolActivity(answer.tools, event.activity) }))
            if (event.activity.status === 'SUCCEEDED' && CART_WRITE_TOOLS.includes(event.activity.tool)) {
              void queryClient.invalidateQueries({ queryKey: cartKeys.all })
            }
            return
          case 'approval':
            storeApproval(queryClient, event.approval)
            update((answer) => ({ ...answer, approvals: mergeApproval(answer.approvals, event.approval) }))
            return
          case 'token':
            answerText += event.text
            update((answer) => ({ ...answer, content: answer.content + event.text }))
            return
          case 'done':
            update((answer) => ({ ...answer, status: 'done' }))
            onAnswerDoneRef.current?.({ key: answerKey, content: answerText, spoken })
            return
          case 'error':
            update((answer) => ({ ...answer, status: 'error', error: { code: event.code, message: event.message } }))
            return
          case 'notice':
            update((answer) => ({ ...answer, notice: { code: event.code, message: event.message } }))
            return
          case 'retry':
            // The answer so far did not use the app's data although it had to: it is replaced by the next one.
            answerText = ''
            update((answer) => ({ ...answer, content: '', notice: null, checking: event.message }))
            return
          case 'sources':
            update((answer) => ({ ...answer, sources: event.sources }))
            return
        }
      }

      void streamChat({ agent, conversationId: conversationRef.current, message, onEvent, signal: controller.signal })
        .then((outcome) => {
          if (outcome === 'aborted') update((answer) => ({ ...answer, status: 'cancelled' }))
          else if (outcome === 'interrupted') update((answer) => ({ ...answer, status: 'interrupted' }))
        })
        .catch((error: unknown) => {
          const failure = chatErrorMessage(error)
          update((answer) => ({ ...answer, status: 'error', error: failure }))
          if (failure.code === AI_ERRORS.conversationNotFound && generation === generationRef.current) setConversation(null)
        })
        .finally(() => {
          if (controllerRef.current === controller) controllerRef.current = null
          if (generation === generationRef.current) setStreaming(false)
          // A new conversation appears in the list; an existing one moves to the top.
          void queryClient.invalidateQueries({ queryKey: aiKeys.conversations() })
        })
    },
    [agent, queryClient, setConversation],
  )

  const cancel = useCallback(() => {
    controllerRef.current?.abort()
  }, [])

  /** Leaves the current conversation: a running answer is cancelled (the server keeps what it stored). */
  const reset = useCallback(() => {
    generationRef.current += 1
    controllerRef.current?.abort()
    controllerRef.current = null
    setStreaming(false)
    setLoadError(null)
    setLoading(false)
    return generationRef.current
  }, [])

  const startNew = useCallback(() => {
    reset()
    setConversation(null)
    setMessages([])
  }, [reset, setConversation])

  const open = useCallback(
    (id: string) => {
      const generation = reset()
      setConversation(id)
      setMessages([])
      setLoading(true)
      queryClient
        .fetchQuery({ queryKey: aiKeys.conversation(id), queryFn: () => fetchConversation(id), staleTime: 0 })
        .then((detail) => {
          if (generation !== generationRef.current) return
          // The stored approvals are the current ones: the cards read them from the cache.
          for (const message of detail.messages) for (const approval of message.approvals) storeApproval(queryClient, approval)
          setMessages(detail.messages.map(fromHistory))
        })
        .catch((error: unknown) => {
          if (generation !== generationRef.current) return
          setLoadError(hasErrorCode(error, AI_ERRORS.conversationNotFound) ? 'This conversation no longer exists.' : errorMessage(error))
          if (hasErrorCode(error, AI_ERRORS.conversationNotFound)) setConversation(null)
        })
        .finally(() => {
          if (generation === generationRef.current) setLoading(false)
        })
    },
    [queryClient, reset, setConversation],
  )

  return { conversationId, messages, streaming, loading, loadError, send, cancel, startNew, open }
}
