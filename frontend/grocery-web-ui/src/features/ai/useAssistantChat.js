import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, errorMessage, hasErrorCode, problemNumber } from '../../shared/api/problemDetail'
import { cartKeys } from '../cart/api'
import { AI_ERRORS, aiKeys, fetchConversation } from './api'
import { streamChat, UNVERIFIED_ANSWER_MESSAGE } from './chatStream'
import { storeApproval } from './hooks'
import { CART_WRITE_TOOLS } from './labels'

/** @typedef {import('./api').ApprovalView} ApprovalView */
/** @typedef {import('./api').ConversationMessage} ConversationMessage */
/** @typedef {import('./api').ToolActivity} ToolActivity */

/**
 * `streaming` while the answer is written; how it ended otherwise.
 * @typedef {'streaming' | 'done' | 'error' | 'cancelled' | 'interrupted'} TurnStatus
 */

/**
 * @typedef {object} ChatMessage
 * @property {string} key
 * @property {'USER' | 'ASSISTANT'} role
 * @property {string} content
 * @property {ToolActivity[]} tools
 * @property {ApprovalView[]} approvals
 * @property {TurnStatus} status
 * @property {{ code: string | null, message: string } | null} error A failed turn: the stream's `error` event or a refused request, in user-friendly words.
 * @property {boolean} [spoken] A question asked by voice in this session (its answer is read aloud when it is complete).
 * @property {{ code: string, message: string } | null} [notice] An answer stating figures that no tool returned: shown as not checked against the app's data.
 * @property {string | null} [checking] The streamed text was dropped and the answer is being checked again (live `retry` event), until new text comes.
 */

/**
 * @typedef {object} SendOptions
 * @property {boolean} [voice] The question was spoken (the microphone filled the input).
 */

/**
 * A complete answer, for whoever reads answers aloud.
 * @typedef {object} CompletedAnswer
 * @property {string} key
 * @property {string} content
 * @property {boolean} spoken Its question was spoken.
 */

/**
 * @typedef {object} AssistantChatOptions
 * @property {(answer: CompletedAnswer) => void} [onAnswerDone] Called once an answer has streamed completely (`done`).
 */

/**
 * @typedef {object} AssistantChat
 * @property {string | null} conversationId
 * @property {ChatMessage[]} messages
 * @property {boolean} streaming An answer is being streamed.
 * @property {boolean} loading A stored conversation is being loaded.
 * @property {string | null} loadError
 * @property {(message: string, options?: SendOptions) => void} send
 * @property {() => void} cancel Stops the answer being written (the part received so far stays).
 * @property {() => void} startNew
 * @property {(conversationId: string) => void} open
 */

let sequence = 0
/**
 * @param {string} prefix
 * @returns {string}
 */
function nextKey(prefix) {
  sequence += 1
  return `${prefix}-${sequence}`
}

/**
 * Tool events arrive per state (STARTED, then SUCCEEDED …): one chip per invocation, in first-seen order.
 * @param {readonly ToolActivity[]} tools
 * @param {ToolActivity} activity
 * @returns {ToolActivity[]}
 */
export function mergeToolActivity(tools, activity) {
  const index = tools.findIndex((tool) => tool.invocationId === activity.invocationId)
  if (index === -1) return [...tools, activity]
  return tools.map((tool, position) =>
    position === index ? { ...tool, ...activity, summary: activity.summary ?? tool.summary } : tool,
  )
}

/**
 * @param {readonly ApprovalView[]} approvals
 * @param {ApprovalView} approval
 * @returns {ApprovalView[]}
 */
function mergeApproval(approvals, approval) {
  return approvals.some((entry) => entry.id === approval.id)
    ? approvals.map((entry) => (entry.id === approval.id ? approval : entry))
    : [...approvals, approval]
}

/**
 * A refused chat request in the assistant's own words (the rest of the app is not affected).
 * @param {unknown} error
 * @returns {{ code: string | null, message: string }}
 */
export function chatErrorMessage(error) {
  if (hasErrorCode(error, AI_ERRORS.rateLimited)) {
    const seconds = problemNumber(error, 'retryAfter')
    const wait =
      seconds !== null && seconds > 0
        ? ` Try again in about ${Math.max(1, Math.ceil(seconds / 60))} min.`
        : ' Try again later.'
    return {
      code: AI_ERRORS.rateLimited,
      message: `You have sent the assistant a lot of messages in the last hour.${wait} Everything else works as usual.`,
    }
  }
  if (hasErrorCode(error, AI_ERRORS.conversationNotFound)) {
    return {
      code: AI_ERRORS.conversationNotFound,
      message: 'This conversation no longer exists. Start a new one to continue.',
    }
  }
  if (hasErrorCode(error, AI_ERRORS.agentNotAllowed, AI_ERRORS.agentNotFound)) {
    return { code: error.errorCode, message: 'This assistant is not available for your account.' }
  }
  if (
    error instanceof ApiError &&
    (error.kind === 'network' || error.kind === 'timeout' || (error.status !== null && error.status >= 500))
  ) {
    return {
      code: 'AI_UNAVAILABLE',
      message: 'The assistant is not available right now. Everything else works as usual.',
    }
  }
  return { code: error instanceof ApiError ? error.errorCode : null, message: errorMessage(error) }
}

/**
 * @param {ConversationMessage} message
 * @returns {ChatMessage}
 */
function fromHistory(message) {
  return {
    key: message.id,
    role: message.role === 'USER' ? 'USER' : 'ASSISTANT',
    content: message.content,
    tools: message.tools,
    approvals: message.approvals,
    status: 'done',
    error: null,
    notice: message.unverified ? { code: 'UNVERIFIED_FIGURES', message: UNVERIFIED_ANSWER_MESSAGE } : null,
  }
}

/**
 * The open conversation of the assistant panel: sends a message, reads the streamed answer event by event into
 * the last assistant message (tool chips, approval cards, text), and keeps the cart and the approval cards'
 * cache current. One answer at a time; switching or leaving cancels it.
 * @param {string} agent
 * @param {AssistantChatOptions} [options={}]
 * @returns {AssistantChat}
 */
export function useAssistantChat(agent, options = {}) {
  const queryClient = useQueryClient()
  const onAnswerDoneRef = useRef(options.onAnswerDone)
  useEffect(() => {
    onAnswerDoneRef.current = options.onAnswerDone
  })
  const [conversationId, setConversationId] = useState(null)
  const [messages, setMessages] = useState([])
  const [streaming, setStreaming] = useState(false)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState(null)
  const controllerRef = useRef(null)
  /** Bumped by every switch (new / open); late answers of an older one are dropped. */
  const generationRef = useRef(0)
  const conversationRef = useRef(null)

  useEffect(
    () => () => {
      generationRef.current += 1
      controllerRef.current?.abort()
    },
    [],
  )

  const setConversation = useCallback((id) => {
    conversationRef.current = id
    setConversationId(id)
  }, [])

  const send = useCallback(
    (text, sendOptions = {}) => {
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
        {
          key: nextKey('user'),
          role: 'USER',
          content: message,
          tools: [],
          approvals: [],
          status: 'done',
          error: null,
          spoken,
        },
        { key: answerKey, role: 'ASSISTANT', content: '', tools: [], approvals: [], status: 'streaming', error: null },
      ])

      const update = (change) => {
        if (generation !== generationRef.current) return
        setMessages((current) => current.map((entry) => (entry.key === answerKey ? change(entry) : entry)))
      }

      const onEvent = (event) => {
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
        }
      }

      void streamChat({ agent, conversationId: conversationRef.current, message, onEvent, signal: controller.signal })
        .then((outcome) => {
          if (outcome === 'aborted') update((answer) => ({ ...answer, status: 'cancelled' }))
          else if (outcome === 'interrupted') update((answer) => ({ ...answer, status: 'interrupted' }))
        })
        .catch((error) => {
          const failure = chatErrorMessage(error)
          update((answer) => ({ ...answer, status: 'error', error: failure }))
          if (failure.code === AI_ERRORS.conversationNotFound && generation === generationRef.current)
            setConversation(null)
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
    (id) => {
      const generation = reset()
      setConversation(id)
      setMessages([])
      setLoading(true)
      queryClient
        .fetchQuery({ queryKey: aiKeys.conversation(id), queryFn: () => fetchConversation(id), staleTime: 0 })
        .then((detail) => {
          if (generation !== generationRef.current) return
          // The stored approvals are the current ones: the cards read them from the cache.
          for (const message of detail.messages)
            for (const approval of message.approvals) storeApproval(queryClient, approval)
          setMessages(detail.messages.map(fromHistory))
        })
        .catch((error) => {
          if (generation !== generationRef.current) return
          setLoadError(
            hasErrorCode(error, AI_ERRORS.conversationNotFound)
              ? 'This conversation no longer exists.'
              : errorMessage(error),
          )
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
