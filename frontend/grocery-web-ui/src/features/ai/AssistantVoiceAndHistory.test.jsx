import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { aiStatus, SHOPPING_AGENT } from '../../test/aiFixtures'
import { cartView } from '../../test/cartFixtures'
import {
  installFakeAudioPlayback,
  installFakeMicrophone,
  installFakeSpeechApi,
  uninstallFakeVoice,
} from '../../test/fakeVoice'
import { mockApi } from '../../test/mockApi'
import { mockChatStream } from '../../test/mockChatStream'
import { pageOf } from '../../test/orderFixtures'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'

const SERVER_VOICE = {
  mode: 'SERVER',
  transcription: true,
  speech: true,
  maxRecordingSeconds: 60,
  maxAudioBytes: 10_485_760,
  maxSpeechCharacters: 4000,
}
const BROWSER_VOICE = { ...SERVER_VOICE, mode: 'BROWSER', transcription: false, speech: false }

function customer(voice, routes = {}) {
  signInAs(['CUSTOMER'])
  return mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /ai/agents': { status: 200, data: [SHOPPING_AGENT] },
    'GET /ai/status': { status: 200, data: aiStatus() },
    'GET /ai/voice/status': { status: 200, data: voice },
    'GET /ai/conversations': pageOf([]),
    'GET /cart': { status: 200, data: cartView([]) },
    'GET /orders': pageOf([]),
    ...routes,
  })
}

async function openPanel(user) {
  await user.click(await screen.findByRole('button', { name: 'Open the assistant' }))
  return screen.findByRole('dialog', { name: 'Shopping assistant' })
}

function input(panel) {
  return within(panel).getByRole('textbox', { name: 'Message the assistant' })
}

afterEach(() => uninstallFakeVoice())

describe('Assistant — chat history from the server', () => {
  const conversation = (id, title, minutesAgo) => ({
    id,
    agent: 'shopping',
    title,
    createdAt: '2026-11-13T09:00:00Z',
    updatedAt: new Date(Date.now() - minutesAgo * 60_000).toISOString(),
  })

  it('reopens the latest conversation after a reload; "New conversation" starts another one', async () => {
    const user = userEvent.setup()
    const api = customer(SERVER_VOICE, {
      'GET /ai/conversations': pageOf([conversation('c9', 'where is my order', 2), conversation('c1', 'add rice', 90)]),
      'GET /ai/conversations/:id': {
        status: 200,
        data: {
          id: 'c9',
          agent: 'shopping',
          title: 'where is my order',
          messages: [
            {
              id: 'm1',
              role: 'USER',
              content: 'where is my order',
              createdAt: '2026-11-15T09:00:00Z',
              tools: [],
              approvals: [],
            },
            {
              id: 'm2',
              role: 'ASSISTANT',
              content: 'Order GA-1 is out for delivery.',
              createdAt: '2026-11-15T09:00:04Z',
              tools: [],
              approvals: [],
            },
          ],
        },
      },
    })
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user)

    // Nothing clicked: the most recent conversation is back, read from the server.
    const log = within(panel).getByRole('log', { name: 'Conversation' })
    expect(await within(log).findByText('Order GA-1 is out for delivery.')).toBeInTheDocument()
    expect(api.callsTo('GET /ai/conversations/:id').map((call) => call.path)).toEqual(['/ai/conversations/c9'])

    // The next question continues it.
    await user.type(input(panel), 'and when will it arrive?')
    await user.click(within(panel).getByRole('button', { name: 'Send' }))
    const first = await chat.connection()
    expect(first.request.body).toEqual({ conversationId: 'c9', message: 'and when will it arrive?' })
    first.done()

    // A new conversation has no id until the server gives it one.
    await user.click(within(panel).getByRole('button', { name: 'New conversation' }))
    expect(await within(panel).findByRole('list', { name: 'Example prompts' })).toBeInTheDocument()
    await user.type(input(panel), 'show my cart')
    await user.click(within(panel).getByRole('button', { name: 'Send' }))
    expect((await chat.connection(1)).request.body).toEqual({ message: 'show my cart' })
  })

  it('a retry replaces the text streamed so far with the checked answer', async () => {
    const user = userEvent.setup()
    customer(SERVER_VOICE)
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user)
    const log = within(panel).getByRole('log', { name: 'Conversation' })

    await user.type(input(panel), 'add 2 rice')
    await user.click(within(panel).getByRole('button', { name: 'Send' }))
    const stream = await chat.connection()
    stream.token("I've added 2 rice to your cart.")
    expect(await within(log).findByText("I've added 2 rice to your cart.")).toBeInTheDocument()
    stream.send('retry', { code: 'ACTION', message: 'Checking that with the app…' })
    expect(await within(log).findByText('Checking that with the app…')).toBeInTheDocument()
    expect(within(log).queryByText("I've added 2 rice to your cart.")).not.toBeInTheDocument()
    stream.token('Added 2 × Smart Rice. Your cart has 3 items.')
    stream.done()
    expect(await within(log).findByText('Added 2 × Smart Rice. Your cart has 3 items.')).toBeInTheDocument()
    expect(within(log).queryByText('Checking that with the app…')).not.toBeInTheDocument()
  })

  it('shows the help-center articles an answer is based on, live and when reopened', async () => {
    const user = userEvent.setup()
    customer(SERVER_VOICE, {
      'GET /ai/conversations': pageOf([conversation('c6', 'refund policy', 1)]),
      'GET /ai/conversations/:id': {
        status: 200,
        data: {
          id: 'c6',
          agent: 'shopping',
          title: 'refund policy',
          messages: [
            {
              id: 'm1',
              role: 'USER',
              content: 'refund policy',
              createdAt: '2026-11-25T09:00:00Z',
              tools: [],
              approvals: [],
            },
            {
              id: 'm2',
              role: 'ASSISTANT',
              content: 'Paid orders that are cancelled are refunded automatically.',
              createdAt: '2026-11-25T09:00:04Z',
              tools: [],
              approvals: [],
              sources: [{ documentId: 'd1', title: 'Refund policy', category: 'POLICIES' }],
            },
          ],
        },
      },
    })
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user)
    const log = within(panel).getByRole('log', { name: 'Conversation' })
    expect(await within(log).findByText('Refund policy')).toBeInTheDocument()
    expect(within(log).getByText('From the help center:')).toBeInTheDocument()

    await user.type(input(panel), 'and delivery charges?')
    await user.click(within(panel).getByRole('button', { name: 'Send' }))
    const stream = await chat.connection()
    stream.token('Delivery is charged by distance.')
    stream.send('sources', { sources: [{ documentId: 'd2', title: 'Delivery charges', category: 'POLICIES' }] })
    stream.done()
    expect(await within(log).findByText('Delivery charges')).toBeInTheDocument()
    expect(within(log).getAllByTestId('answer-sources')).toHaveLength(2)
  })

  it('an answer with figures no tool returned is marked as not checked, live and when reopened', async () => {
    const user = userEvent.setup()
    customer(SERVER_VOICE, {
      'GET /ai/conversations': pageOf([conversation('c5', 'how many vendors', 1)]),
      'GET /ai/conversations/:id': {
        status: 200,
        data: {
          id: 'c5',
          agent: 'shopping',
          title: 'how many vendors',
          messages: [
            {
              id: 'm1',
              role: 'USER',
              content: 'how many vendors',
              createdAt: '2026-11-15T09:00:00Z',
              tools: [],
              approvals: [],
            },
            {
              id: 'm2',
              role: 'ASSISTANT',
              content: 'There are 112 vendors.',
              createdAt: '2026-11-15T09:00:04Z',
              tools: [],
              approvals: [],
              unverified: true,
            },
          ],
        },
      },
    })
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user)
    const log = within(panel).getByRole('log', { name: 'Conversation' })
    expect(await within(log).findByText(/not checked against the app's data/)).toBeInTheDocument()

    await user.type(input(panel), 'and restaurants?')
    await user.click(within(panel).getByRole('button', { name: 'Send' }))
    const stream = await chat.connection()
    stream.token('There are 40 restaurants.')
    stream.send('notice', { code: 'UNVERIFIED_FIGURES', message: 'Not checked against live data.' })
    stream.done()
    expect(await within(log).findByText('Not checked against live data.')).toBeInTheDocument()
  })

  it('renames a conversation; Escape keeps the old name', async () => {
    const user = userEvent.setup()
    const server = { conversations: [conversation('c1', 'add 2 rice', 5)] }
    const api = customer(SERVER_VOICE, {
      'GET /ai/conversations': () => pageOf(server.conversations),
      'GET /ai/conversations/:id': {
        status: 200,
        data: { id: 'c1', agent: 'shopping', title: 'add 2 rice', messages: [] },
      },
      'PATCH /ai/conversations/:id': (request) => {
        const body = request.body
        server.conversations = server.conversations.map((entry) =>
          entry.id === request.pathParams.id ? { ...entry, title: body.title } : entry,
        )
        return { status: 200, data: server.conversations[0] }
      },
    })
    mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user)
    await user.click(within(panel).getByRole('button', { name: 'Conversations' }))
    const list = await within(panel).findByRole('list', { name: 'Conversations' })

    await user.click(within(list).getByRole('button', { name: 'Rename conversation “add 2 rice”' }))
    await user.keyboard('{Escape}')
    expect(within(list).getByRole('button', { name: /^add 2 rice/ })).toBeInTheDocument()
    expect(api.callsTo('PATCH /ai/conversations/:id')).toHaveLength(0)

    await user.click(within(list).getByRole('button', { name: 'Rename conversation “add 2 rice”' }))
    const field = within(list).getByRole('textbox', { name: 'New name for “add 2 rice”' })
    await user.clear(field)
    await user.type(field, '  Weekly   groceries {Enter}')
    await waitFor(() => expect(api.callsTo('PATCH /ai/conversations/:id')).toHaveLength(1))
    expect(api.callsTo('PATCH /ai/conversations/:id')[0]?.body).toEqual({ title: 'Weekly groceries' })
    expect(await within(list).findByRole('button', { name: /^Weekly groceries/ })).toBeInTheDocument()
  })
})

describe('Assistant — voice through the server', () => {
  it('records a question into the input, sends it, and reads the answer aloud; Stop stops it', async () => {
    const user = userEvent.setup()
    const microphone = installFakeMicrophone()
    const playback = installFakeAudioPlayback()
    const api = customer(SERVER_VOICE, {
      'POST /ai/voice/transcriptions': { status: 200, data: { text: 'Where is my latest order?' } },
      'POST /ai/voice/speech': { status: 200, data: new Blob(['ID3'], { type: 'audio/mpeg' }) },
    })
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user)

    await user.click(await within(panel).findByRole('button', { name: 'Speak your question' }))
    expect(await within(panel).findByText('Listening…')).toBeInTheDocument()
    expect(microphone.getUserMedia).toHaveBeenCalledWith({ audio: { echoCancellation: true, noiseSuppression: true } })
    expect(within(panel).getByRole('button', { name: 'Send' })).toBeDisabled()
    await act(() => new Promise((resolve) => setTimeout(resolve, 600)))
    await user.click(within(panel).getByRole('button', { name: 'Stop recording' }))

    // The words go into the input for the user to check; the microphone is off again.
    await waitFor(() => expect(input(panel)).toHaveValue('Where is my latest order?'))
    expect(microphone.track.stop).toHaveBeenCalled()
    const upload = api.callsTo('POST /ai/voice/transcriptions')[0]?.body
    expect(upload).toBeInstanceOf(FormData)
    expect(upload.get('audio')).toBeInstanceOf(Blob)
    expect(api.callsTo('POST /ai/voice/speech')).toHaveLength(0)

    await user.click(within(panel).getByRole('button', { name: 'Send' }))
    const stream = await chat.connection()
    expect(stream.request.body).toEqual({ message: 'Where is my latest order?' })
    expect(within(panel).getByText('Spoken')).toBeInTheDocument()
    stream.conversation('c1')
    stream.token('Order GA-1 is **out for delivery**.')
    stream.done()

    // A spoken question's answer is read aloud by itself.
    await waitFor(() => expect(api.callsTo('POST /ai/voice/speech')).toHaveLength(1))
    expect(api.callsTo('POST /ai/voice/speech')[0]?.body).toEqual({ text: 'Order GA-1 is **out for delivery**.' })
    await waitFor(() => expect(playback.players[0]?.play).toHaveBeenCalled())
    const stop = await within(panel).findByRole('button', { name: 'Stop reading this answer' })
    await user.click(stop)
    expect(playback.players[0]?.pause).toHaveBeenCalled()
    expect(within(panel).getByRole('button', { name: 'Read this answer aloud' })).toBeInTheDocument()
  })

  it('a typed question is not read aloud, but any answer can be on request', async () => {
    const user = userEvent.setup()
    installFakeMicrophone()
    const playback = installFakeAudioPlayback()
    const api = customer(SERVER_VOICE, {
      'POST /ai/voice/speech': { status: 200, data: new Blob(['ID3'], { type: 'audio/mpeg' }) },
    })
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user)

    await user.type(input(panel), 'show my cart')
    await user.click(within(panel).getByRole('button', { name: 'Send' }))
    const stream = await chat.connection()
    stream.token('Your cart is empty.')
    stream.done()
    const listen = await within(panel).findByRole('button', { name: 'Read this answer aloud' })
    expect(api.callsTo('POST /ai/voice/speech')).toHaveLength(0)

    await user.click(listen)
    await waitFor(() => expect(playback.players[0]?.play).toHaveBeenCalled())
    act(() => playback.players[0]?.finish())
    expect(await within(panel).findByRole('button', { name: 'Read this answer aloud' })).toBeInTheDocument()
  })

  it('a blocked microphone is explained and typing still works', async () => {
    const user = userEvent.setup()
    installFakeMicrophone({ deny: true })
    customer(SERVER_VOICE)
    mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user)

    await user.click(await within(panel).findByRole('button', { name: 'Speak your question' }))
    expect(await within(panel).findByText(/Microphone access is blocked/)).toBeInTheDocument()
    expect(input(panel)).toBeEnabled()
    await user.click(within(panel).getByRole('button', { name: 'Dismiss the voice message' }))
    expect(within(panel).queryByText(/Microphone access is blocked/)).not.toBeInTheDocument()
  })

  it('a failed transcription says so in plain words', async () => {
    const user = userEvent.setup()
    installFakeMicrophone()
    customer(SERVER_VOICE, {
      'POST /ai/voice/transcriptions': {
        status: 422,
        data: { status: 422, detail: 'No words', errorCode: 'NO_SPEECH_DETECTED' },
      },
    })
    mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user)

    await user.click(await within(panel).findByRole('button', { name: 'Speak your question' }))
    await within(panel).findByText('Listening…')
    await act(() => new Promise((resolve) => setTimeout(resolve, 600)))
    await user.click(within(panel).getByRole('button', { name: 'Stop recording' }))
    expect(await within(panel).findByText(/I did not catch any words/)).toBeInTheDocument()
    expect(input(panel)).toHaveValue('')
  })

  it('Cancel throws the recording away', async () => {
    const user = userEvent.setup()
    const microphone = installFakeMicrophone()
    const api = customer(SERVER_VOICE)
    mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user)

    await user.click(await within(panel).findByRole('button', { name: 'Speak your question' }))
    await within(panel).findByText('Listening…')
    await user.click(within(panel).getByRole('button', { name: 'Cancel' }))
    expect(within(panel).queryByText('Listening…')).not.toBeInTheDocument()
    expect(microphone.track.stop).toHaveBeenCalled()
    expect(api.callsTo('POST /ai/voice/transcriptions')).toHaveLength(0)
  })
})

describe('Assistant — voice in the browser', () => {
  it('uses the browser’s own speech recognition and reads the answer with its own voice', async () => {
    const user = userEvent.setup()
    const speech = installFakeSpeechApi()
    const api = customer(BROWSER_VOICE)
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user)

    await user.click(await within(panel).findByRole('button', { name: 'Speak your question' }))
    const recognition = speech.recognitions[0]
    expect(recognition?.start).toHaveBeenCalled()
    expect(recognition?.interimResults).toBe(true)
    act(() => recognition?.hear('show my cart'))
    await waitFor(() => expect(input(panel)).toHaveValue('show my cart'))

    await user.click(within(panel).getByRole('button', { name: 'Send' }))
    const stream = await chat.connection()
    stream.token('Your cart is empty. Add something first.')
    stream.done()
    await waitFor(() => expect(speech.spoken).toEqual(['Your cart is empty. Add something first.']))
    expect(api.callsTo('POST /ai/voice/transcriptions')).toHaveLength(0)
    expect(api.callsTo('POST /ai/voice/speech')).toHaveLength(0)
  })

  it('no microphone and no Listen button when voice is off', async () => {
    const user = userEvent.setup()
    customer({ ...SERVER_VOICE, mode: 'OFF', transcription: false, speech: false })
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user)

    await user.type(input(panel), 'show my cart')
    await user.click(within(panel).getByRole('button', { name: 'Send' }))
    const stream = await chat.connection()
    stream.token('Your cart is empty.')
    stream.done()
    expect(await within(panel).findByText('Your cart is empty.')).toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: 'Speak your question' })).not.toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: 'Read this answer aloud' })).not.toBeInTheDocument()
  })
})
