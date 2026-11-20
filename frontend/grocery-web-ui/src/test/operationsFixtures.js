import { problem } from './mockApi'
import { pageOf } from './orderFixtures'

/** The contract's example: notification-service could not process a VendorSubmitted event. */
export function deadLetter(overrides = {}) {
  return {
    id: 'vendor.events.DLT:0:12',
    dltTopic: 'vendor.events.DLT',
    originalTopic: 'vendor.events',
    originalPartition: 1,
    originalOffset: 845,
    consumerGroup: 'notification-service.dispatch',
    key: 'v1',
    eventId: 'evt-1',
    eventType: 'VendorSubmitted',
    eventVersion: 1,
    occurredAt: '2026-11-12T09:59:58Z',
    deadLetteredAt: '2026-11-12T10:00:00Z',
    exceptionClass: 'com.grocery.common.kafka.InvalidEventException',
    exceptionMessage: 'Field businessName is required',
    payloadPreview: '{"vendorId":"v1"}',
    status: 'PENDING',
    attempts: 0,
    lastError: null,
    lastActionBy: null,
    lastActionAt: null,
    ...overrides,
  }
}

export function deadLetterDetail(overrides = {}) {
  return {
    ...deadLetter(),
    payload: { vendorId: 'v1', businessName: null, city: 'Bengaluru' },
    stackTrace:
      'com.grocery.common.kafka.InvalidEventException: Field businessName is required\n\tat com.grocery.notification.VendorListener.on(VendorListener.java:42)',
    ...overrides,
  }
}

/**
 * An in-memory dead-letter API for every service behind `/api/admin/ops/<service>/dead-letters`: lists filter by
 * status / event type / consumer group (newest first), a service listed in `down` answers 503.
 */
export function deadLetterServer(letters, down = []) {
  const server = { letters, down }
  const find = (service, id) => (server.letters[service] ?? []).find((entry) => entry.id === id)
  const update = (service, id, changes) => {
    const list = server.letters[service] ?? []
    server.letters[service] = list.map((entry) => (entry.id === id ? { ...entry, ...changes } : entry))
    return find(service, id)
  }
  const routes = {
    'GET /admin/ops/:service/dead-letters': (request) => {
      const { service = '' } = request.pathParams
      if (server.down.includes(service))
        return problem(503, 'SERVICE_UNAVAILABLE', `The ${service} service is unavailable`)
      const { status, eventType, consumerGroup } = request.params
      const rows = (server.letters[service] ?? [])
        .filter(
          (entry) =>
            (!status || String(status).split(',').includes(entry.status)) &&
            (!eventType || entry.eventType === eventType) &&
            (!consumerGroup || entry.consumerGroup === consumerGroup),
        )
        .sort((a, b) => b.deadLetteredAt.localeCompare(a.deadLetteredAt))
      const size = Number(request.params.size ?? 20)
      return pageOf(rows.slice(0, size), {
        size,
        totalElements: rows.length,
        totalPages: Math.ceil(rows.length / size),
      })
    },
    'GET /admin/ops/:service/dead-letters/:id': (request) => {
      const entry = find(request.pathParams.service ?? '', request.pathParams.id ?? '')
      return entry ? { status: 200, data: deadLetterDetail(entry) } : problem(404, 'DEAD_LETTER_NOT_FOUND')
    },
  }
  return { server, routes, update }
}

/** Kafka Connect's `/connectors?expand=status`: identity-outbox's task failed, order-outbox runs. */
export function connectorsResponse() {
  return {
    'order-outbox': {
      status: {
        name: 'order-outbox',
        connector: { state: 'RUNNING', worker_id: 'kafka-connect:8083' },
        tasks: [{ id: 0, state: 'RUNNING', worker_id: 'kafka-connect:8083' }],
        type: 'source',
      },
    },
    'identity-outbox': {
      status: {
        name: 'identity-outbox',
        connector: { state: 'RUNNING', worker_id: 'kafka-connect:8083' },
        tasks: [
          {
            id: 0,
            state: 'FAILED',
            worker_id: 'kafka-connect:8083',
            trace: 'org.apache.kafka.connect.errors.ConnectException: payload_missing column does not exist',
          },
        ],
        type: 'source',
      },
    },
  }
}

export function healthyConnectors() {
  const response = connectorsResponse()
  const identity = response['identity-outbox']?.status
  if (identity?.tasks?.[0]) identity.tasks[0] = { id: 0, state: 'RUNNING', worker_id: 'kafka-connect:8083' }
  return response
}

export function stuckOrder(overrides = {}) {
  return {
    orderId: 'o1',
    orderNumber: 'GA-7K3F9Q2M',
    type: 'GROCERY',
    status: 'CONFIRMED',
    reason: 'STORE_RESPONSE_OVERDUE',
    since: '2026-11-12T09:00:00Z',
    detectedAt: '2026-11-12T09:15:00Z',
    minutesOverdue: 85,
    store: { id: 'v1', name: 'Green Basket' },
    customerId: 'cust-1',
    ...overrides,
  }
}

export function orderEvent(overrides = {}) {
  return {
    eventId: 'e1',
    eventType: 'OrderCreated',
    sourceTopic: 'order.events',
    direction: 'PUBLISHED',
    outcome: 'APPLIED',
    detail: null,
    occurredAt: '2026-11-12T10:00:00Z',
    recordedAt: '2026-11-12T10:00:00.050Z',
    correlationId: 'corr-1',
    ...overrides,
  }
}
