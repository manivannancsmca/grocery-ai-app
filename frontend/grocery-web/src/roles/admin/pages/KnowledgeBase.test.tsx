import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { KnowledgeDocument } from '../../../features/knowledge/api'
import { mockApi, problem } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

function knowledgeDocument(overrides: Partial<KnowledgeDocument> = {}): KnowledgeDocument {
  return {
    id: 'd1',
    title: 'Refund policy',
    category: 'POLICIES',
    audiences: ['CUSTOMER', 'STAFF'],
    fileName: 'refund-policy.pdf',
    format: 'PDF',
    sizeBytes: 48_000,
    contentVersion: 1,
    status: 'COMPLETED',
    searchable: true,
    chunkCount: 6,
    embeddingModel: 'nomic-embed-text',
    failureReason: null,
    createdBy: { id: 'admin-1', name: 'Dev Ops' },
    updatedBy: { id: 'admin-1', name: 'Dev Ops' },
    createdAt: '2026-11-25T09:00:00Z',
    updatedAt: '2026-11-25T09:01:00Z',
    processedAt: '2026-11-25T09:01:00Z',
    ...overrides,
  }
}

const refunds = knowledgeDocument()
const failedVersion = knowledgeDocument({
  id: 'd2',
  title: 'Delivery charges',
  fileName: 'delivery.docx',
  format: 'DOCX',
  contentVersion: 2,
  status: 'FAILED',
  failureReason: 'The embedding model could not be reached.',
})

function setup(role: Parameters<typeof signInAs>[0][number], routes: Parameters<typeof mockApi>[0] = {}) {
  signInAs([role], { id: 'admin-1' })
  return mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /ai/agents': { status: 200, data: [] },
    'GET /admin/ai/knowledge/documents': pageOf([refunds, failedVersion]),
    ...routes,
  })
}

describe('Help center (admin)', () => {
  it('lists the articles with their readers and status, and explains a failed new version', async () => {
    setup('OPS_ADMIN')
    renderApp('/admin/ai/knowledge')

    expect(await screen.findByRole('heading', { level: 1, name: 'Help center' })).toBeInTheDocument()
    const table = await screen.findByRole('table', { name: 'Help-center articles' })
    expect(within(table).getByText('Refund policy')).toBeInTheDocument()
    expect(within(table).getAllByText('Customers, Staff')).toHaveLength(2)
    expect(within(table).getByText('Ready')).toBeInTheDocument()
    expect(within(table).getByText('6 passages')).toBeInTheDocument()
    expect(within(table).getByText('Failed')).toBeInTheDocument()
    expect(within(table).getByText('The embedding model could not be reached.')).toBeInTheDocument()
    expect(within(table).getByText('The previous version is still used until this one is ready.')).toBeInTheDocument()
  })

  it('adds an article with its category and readers', async () => {
    const user = userEvent.setup()
    const api = setup('SUPER_ADMIN', {
      'POST /admin/ai/knowledge/documents': { status: 202, data: knowledgeDocument({ id: 'd3', title: 'Payment methods', status: 'UPLOADED' }) },
    })
    renderApp('/admin/ai/knowledge')

    const form = (await screen.findByRole('heading', { name: 'Add an article' })).closest('section') ?? document.body
    await user.upload(within(form as HTMLElement).getByLabelText(/^File/), new File(['# Payment methods'], 'payment-methods.md', { type: 'text/markdown' }))
    await user.type(within(form as HTMLElement).getByLabelText(/^Title/), 'Payment methods')
    await user.selectOptions(within(form as HTMLElement).getByLabelText('Category'), 'POLICIES')
    await user.click(within(form as HTMLElement).getByLabelText('Staff'))
    await user.click(within(form as HTMLElement).getByRole('button', { name: 'Add article' }))

    expect(await screen.findByText(/“Payment methods” was added/)).toBeInTheDocument()
    const body = api.callsTo('POST /admin/ai/knowledge/documents')[0]?.body as FormData
    expect((body.get('file') as File).name).toBe('payment-methods.md')
    expect(body.get('title')).toBe('Payment methods')
    expect(body.get('category')).toBe('POLICIES')
    expect(body.getAll('audiences')).toEqual(['CUSTOMER'])
  })

  it('says when the same file is already there', async () => {
    const user = userEvent.setup()
    setup('OPS_ADMIN', { 'POST /admin/ai/knowledge/documents': { status: 200, data: refunds } })
    renderApp('/admin/ai/knowledge')

    await user.upload(await screen.findByLabelText(/^File/), new File(['x'], 'refunds.pdf', { type: 'application/pdf' }))
    await user.click(screen.getByRole('button', { name: 'Add article' }))

    expect(await screen.findByText('This file is already in the help center as “Refund policy”. Nothing was added.')).toBeInTheDocument()
  })

  it('deletes an article after a confirmation', async () => {
    const user = userEvent.setup()
    const api = setup('OPS_ADMIN', { 'DELETE /admin/ai/knowledge/documents/:id': { status: 204, data: null } })
    renderApp('/admin/ai/knowledge')

    const table = await screen.findByRole('table', { name: 'Help-center articles' })
    const row = within(table).getByText('Refund policy').closest('tr') as HTMLElement
    await user.click(within(row).getByRole('button', { name: 'Delete' }))
    const dialog = await screen.findByRole('dialog', { name: 'Delete “Refund policy”?' })
    await user.click(within(dialog).getByRole('button', { name: 'Delete article' }))

    await waitFor(() => expect(api.callsTo('DELETE /admin/ai/knowledge/documents/:id')).toHaveLength(1))
    expect(api.callsTo('DELETE /admin/ai/knowledge/documents/:id')[0]?.pathParams.id).toBe('d1')
  })

  it('is view-only for other admins, who can still test the search', async () => {
    const user = userEvent.setup()
    const api = setup('FINANCE_ADMIN', {
      'POST /admin/ai/knowledge/search': (request) =>
        (request.body as { question: string }).question.includes('refund')
          ? {
              status: 200,
              data: {
                status: 'FOUND',
                results: [{ documentId: 'd1', title: 'Refund policy', category: 'POLICIES', text: 'Paid orders are refunded automatically.', similarity: 0.82 }],
              },
            }
          : { status: 200, data: { status: 'NO_MATCH', results: [] } },
    })
    renderApp('/admin/ai/knowledge')

    expect(await screen.findByText(/Only super admins and operations admins can change the help center/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Add an article' })).not.toBeInTheDocument()
    const table = await screen.findByRole('table', { name: 'Help-center articles' })
    expect(within(table).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument()

    await user.type(screen.getByLabelText('Question'), 'refund of a cancelled order')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    const passages = await screen.findByRole('list', { name: 'Passages found' })
    expect(within(passages).getByText('82% similar')).toBeInTheDocument()
    expect(api.callsTo('POST /admin/ai/knowledge/search')[0]?.body).toEqual({ question: 'refund of a cancelled order', audience: 'CUSTOMER' })

    await user.clear(screen.getByLabelText('Question'))
    await user.type(screen.getByLabelText('Question'), 'bulk catering')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByText(/No article matches/)).toBeInTheDocument()
  })

  it('explains when the knowledge base is switched off', async () => {
    setup('OPS_ADMIN', {
      'GET /admin/ai/knowledge/documents': problem(503, 'KNOWLEDGE_BASE_DISABLED', 'The knowledge base is switched off.'),
    })
    renderApp('/admin/ai/knowledge')

    expect(await screen.findByText('The help center is switched off')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Add an article' })).not.toBeInTheDocument()
  })
})
