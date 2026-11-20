import type { AgentView, AiStatus, AiSummary, ApprovalView, InsightReport, ToolInvocationView } from '../features/ai/api'

export const SHOPPING_AGENT: AgentView = {
  id: 'shopping',
  name: 'Shopping assistant',
  description: 'Finds products and restaurants, fills your cart and prepares your order.',
  examplePrompts: ['add 2 rice', 'show my cart', 'place my order'],
}

export const PAYMENT_AGENT: AgentView = {
  id: 'payment',
  name: 'Payment assistant',
  description: 'Checks your payments: whether one went through, why one failed, how long you have to pay, and your refunds.',
  examplePrompts: ['Did my last payment go through?', 'Where is my refund?'],
}

export const SUPPORT_AGENT: AgentView = {
  id: 'support',
  name: 'Customer support assistant',
  description: 'Helps with your orders: where an order is, cancellations, returns and refunds, and complaints to our support team.',
  examplePrompts: ['Where is my order?', 'I want to raise a complaint'],
}

export const ADMIN_BI_AGENT: AgentView = {
  id: 'admin-bi',
  name: 'Business assistant',
  description: 'Answers questions about orders and payments; proposes refunds and cancellations.',
  examplePrompts: ['sales today', 'stuck orders'],
}

export function aiStatus(overrides: Partial<AiStatus> = {}): AiStatus {
  return { provider: 'ollama', model: 'qwen3:4b', available: true, checkedAt: '2026-11-13T10:00:00Z', ...overrides }
}

/** The contract's example: the customer's PLACE_ORDER, pending, decided by the customer (user-1) within 15 min. */
export function placeOrderApproval(overrides: Partial<ApprovalView> = {}): ApprovalView {
  return {
    id: 'a1',
    action: 'PLACE_ORDER',
    status: 'PENDING_APPROVAL',
    agent: 'shopping',
    conversationId: 'c1',
    requestedBy: 'user-1',
    onBehalfOf: 'user-1',
    approverUserId: 'user-1',
    approverRoles: [],
    fourEyes: false,
    summary: {
      title: 'Place order: 2 items from Fresh Mart',
      lines: [
        { label: 'Smart Rice × 2', value: '₹200.00' },
        { label: 'Delivery fee', value: '₹25.00' },
        { label: 'Deliver to', value: 'Home, 12 MG Road, Bengaluru' },
      ],
      amount: 225,
      currency: 'INR',
    },
    rationale: 'You asked me to check out.',
    fingerprint: '3f9a1c2e',
    canDecide: true,
    // Far enough ahead that the countdown never runs out during a test.
    createdAt: new Date(Date.now() - 60_000).toISOString(),
    expiresAt: new Date(Date.now() + 14 * 60_000 + 30_000).toISOString(),
    decidedBy: null,
    decidedAt: null,
    comment: null,
    executedAt: null,
    result: null,
    failure: null,
    ...overrides,
  }
}

/** A finance refund proposed by the business assistant: four-eyes (above ₹2000), deciders SUPER_ADMIN / FINANCE_ADMIN. */
export function refundApproval(overrides: Partial<ApprovalView> = {}): ApprovalView {
  return placeOrderApproval({
    id: 'a2',
    action: 'REFUND_PAYMENT',
    agent: 'admin-bi',
    conversationId: 'c9',
    requestedBy: 'admin-requester',
    onBehalfOf: null,
    approverUserId: null,
    approverRoles: ['SUPER_ADMIN', 'FINANCE_ADMIN'],
    fourEyes: true,
    summary: {
      title: 'Refund ₹2,500.00 for GA-7K3F9Q2M',
      lines: [
        { label: 'Order', value: 'GA-7K3F9Q2M' },
        { label: 'Paid', value: '₹3,000.00' },
        { label: 'Already refunded', value: '₹0.00' },
        { label: 'Note', value: 'Damaged items' },
      ],
      amount: 2500,
      currency: 'INR',
    },
    rationale: 'The customer reported damaged items.',
    fingerprint: '9b1e77d0',
    expiresAt: new Date(Date.now() + 23 * 3_600_000).toISOString(),
    ...overrides,
  })
}

export function completedOrder(approval: ApprovalView = placeOrderApproval()): ApprovalView {
  return {
    ...approval,
    status: 'COMPLETED',
    canDecide: false,
    decidedBy: 'user-1',
    decidedAt: '2026-11-13T10:05:00Z',
    executedAt: '2026-11-13T10:05:01Z',
    result: { orderId: 'o9', orderNumber: 'GA-AI000001' },
  }
}

export function toolInvocation(overrides: Partial<ToolInvocationView> = {}): ToolInvocationView {
  return {
    id: 't1',
    conversationId: 'c1',
    userId: 'user-1',
    roles: ['CUSTOMER'],
    agent: 'shopping',
    tool: 'searchProducts',
    arguments: { query: 'rice', inStockOnly: true },
    status: 'SUCCEEDED',
    reason: null,
    resultSummary: '3 products found',
    httpStatus: 200,
    latencyMs: 84,
    approvalId: null,
    createdAt: '2026-11-13T10:00:00Z',
    ...overrides,
  }
}

export function aiSummary(overrides: Partial<AiSummary> = {}): AiSummary {
  return {
    date: '2026-11-13',
    conversations: 12,
    messages: 48,
    toolCalls: 61,
    blocked: 3,
    failed: 2,
    approvals: { requested: 7, completed: 4, rejected: 1, expired: 1, failed: 0, cancelled: 0, pending: 1 },
    modelErrors: 1,
    avgModelLatencyMs: 2350,
    provider: 'ollama',
    model: 'qwen3:4b',
    available: true,
    ...overrides,
  }
}

export function insightReport(overrides: Partial<InsightReport> = {}): InsightReport {
  return {
    date: '2026-11-13',
    headline: 'Orders are up; two orders are stuck at the store',
    kpis: [
      { name: 'Orders', value: 42, note: '+12% on yesterday' },
      { name: 'Confirmed revenue', value: '₹10,250' },
    ],
    observations: ['Most orders came from Green Basket.'],
    risks: ['2 orders are waiting for the store for over an hour.'],
    suggestedActions: ['Call Green Basket about GA-7K3F9Q2M.'],
    generatedBy: { provider: 'ollama', model: 'qwen3:4b' },
    generatedAt: '2026-11-13T10:00:00Z',
    ...overrides,
  }
}
