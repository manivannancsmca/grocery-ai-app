import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { ToolInvocationView } from '../../../features/ai/api'
import { aiSummary, toolInvocation } from '../../../test/aiFixtures'
import { mockApi } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const search = toolInvocation()
/** A customer tried an admin tool: ToolGuard blocked it. */
const blocked = toolInvocation({
  id: 't2',
  tool: 'refundOrder',
  arguments: '{"orderNumber":"GA-7K3F9Q2M"}',
  status: 'BLOCKED',
  reason: 'TOOL_NOT_ALLOWED',
  resultSummary: null,
  httpStatus: null,
  latencyMs: 1,
  createdAt: '2026-11-13T10:01:00Z',
})
/** The admin's refund proposal became an approval request. */
const proposal = toolInvocation({
  id: 't3',
  userId: 'admin-1',
  roles: ['SUPPORT'],
  agent: 'admin-bi',
  tool: 'refundOrder',
  status: 'APPROVAL_REQUIRED',
  resultSummary: 'Approval a2 created',
  approvalId: 'a2',
  createdAt: '2026-11-13T10:02:00Z',
})

function setup() {
  signInAs(['SUPPORT'], { id: 'admin-1' })
  const rowsFor = (params: Record<string, unknown>): ToolInvocationView[] =>
    [proposal, blocked, search].filter(
      (row) =>
        (!params.agent || row.agent === params.agent) &&
        (!params.tool || row.tool === params.tool) &&
        (!params.status || row.status === params.status) &&
        (!params.userId || row.userId === params.userId),
    )
  return mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /ai/agents': { status: 200, data: [] },
    'GET /admin/ai/summary': (request) => ({ status: 200, data: aiSummary(request.params.date ? { date: String(request.params.date), conversations: 2 } : {}) }),
    'GET /admin/ai/tool-invocations': (request) => pageOf(rowsFor(request.params)),
  })
}

describe('AI audit (admin)', () => {
  it('shows the day’s summary and every tool call, newest first, with blocked attempts highlighted and explained', async () => {
    const api = setup()
    renderApp('/admin/ai/audit')

    expect(await screen.findByRole('heading', { level: 1, name: 'AI audit' })).toBeInTheDocument()
    const usage = await screen.findByRole('region', { name: 'Assistant usage' })
    await waitFor(() => expect(within(usage).getByText('Conversations').nextElementSibling).toHaveTextContent('12'))
    expect(within(usage).getByText('Tool calls').nextElementSibling).toHaveTextContent('61')
    const problems = screen.getByRole('region', { name: 'Blocked and failed tool calls' })
    expect(within(problems).getByText('Blocked attempts').nextElementSibling).toHaveTextContent('3')
    expect(within(problems).getByText('Blocked attempts').nextElementSibling?.className).toMatch(/text-red/)
    expect(within(problems).getByText('Approvals pending').nextElementSibling).toHaveTextContent('1')
    const activity = screen.getByRole('region', { name: 'AI activity' })
    expect(activity).toHaveTextContent('Model available')
    expect(activity).toHaveTextContent('ollama · qwen3:4b')
    expect(activity).toHaveTextContent('average model latency 2,350 ms')

    const table = await screen.findByRole('table', { name: 'Tool calls' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent('Refund order')
    expect(rows[0]).toHaveTextContent('Approval required')
    expect(within(rows[0] as HTMLElement).getByRole('link', { name: 'Approval request →' })).toHaveAttribute('href', '/admin/ai/approvals?tab=all&id=a2')

    expect(rows[1]).toHaveTextContent('Blocked')
    expect(rows[1]).toHaveTextContent('Tool not allowed for this assistant (TOOL_NOT_ALLOWED)')
    expect(rows[1]).toHaveTextContent('{"orderNumber":"GA-7K3F9Q2M"}')
    expect(rows[1]?.className).toMatch(/bg-red-50/)
    expect(rows[0]?.className).not.toMatch(/bg-red-50/)

    expect(rows[2]).toHaveTextContent('Search products')
    expect(rows[2]).toHaveTextContent('{"query":"rice","inStockOnly":true}')
    expect(rows[2]).toHaveTextContent('84 ms')
    expect(rows[2]).toHaveTextContent('Customer')

    expect(api.callsTo('GET /admin/ai/summary')[0]?.params).toEqual({})
    expect(api.callsTo('GET /admin/ai/tool-invocations')[0]?.params).toEqual({ page: 0, size: 20 })
  })

  it('filters by assistant, tool, status, user and dates (in the URL and the request)', async () => {
    const user = userEvent.setup()
    const api = setup()
    const { router } = renderApp('/admin/ai/audit')
    const table = await screen.findByRole('table', { name: 'Tool calls' })
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(4))

    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'BLOCKED')
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Tool calls' })).getAllByRole('row')).toHaveLength(2))
    expect(screen.getByRole('table', { name: 'Tool calls' })).toHaveTextContent('TOOL_NOT_ALLOWED')

    await user.selectOptions(screen.getByRole('combobox', { name: 'Assistant' }), 'shopping')
    // The tool list follows the assistant: only the shopping tools.
    const toolSelect = screen.getByRole('combobox', { name: 'Tool' })
    expect(within(toolSelect).queryByRole('option', { name: /refundOrder/ })).not.toBeInTheDocument()
    await user.selectOptions(toolSelect, 'searchProducts')
    await user.type(screen.getByRole('searchbox', { name: 'User ID' }), 'user-1')
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-11-01' } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-11-13' } })

    await waitFor(() =>
      expect(api.callsTo('GET /admin/ai/tool-invocations').at(-1)?.params).toEqual({
        agent: 'shopping',
        tool: 'searchProducts',
        status: 'BLOCKED',
        userId: 'user-1',
        from: '2026-11-01',
        to: '2026-11-13',
        page: 0,
        size: 20,
      }),
    )
    expect(await screen.findByText('No tool calls')).toBeInTheDocument()
    const params = new URLSearchParams(router.state.location.search)
    expect(Object.fromEntries(params)).toEqual({
      status: 'BLOCKED',
      agent: 'shopping',
      tool: 'searchProducts',
      userId: 'user-1',
      from: '2026-11-01',
      to: '2026-11-13',
    })
  })

  it('reads the summary of another day', async () => {
    const api = setup()
    renderApp('/admin/ai/audit')
    await screen.findByRole('table', { name: 'Tool calls' })

    fireEvent.change(screen.getByLabelText('Summary day'), { target: { value: '2026-11-10' } })
    await waitFor(() => expect(api.callsTo('GET /admin/ai/summary').at(-1)?.params).toEqual({ date: '2026-11-10' }))
    const usage = screen.getByRole('region', { name: 'Assistant usage' })
    await waitFor(() => expect(within(usage).getByText('Conversations').nextElementSibling).toHaveTextContent('2'))
  })
})
