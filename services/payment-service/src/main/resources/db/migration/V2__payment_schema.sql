-- Payment model. IDs: UUIDv7 as BINARY(16); money DECIMAL(12,2) in INR; timestamps DATETIME(6) in UTC.
-- Lock order (see service/PaymentLocks.java): payment row first, then its refund rows. No provider (Razorpay) call is
-- made while a transaction holds a row lock, except the creation of the Razorpay order (README "One Razorpay order
-- per payment").

-- One payment per order (created from order.events → OrderAwaitingPayment).
CREATE TABLE payment (
    id                   BINARY(16)     NOT NULL,
    order_id             BINARY(16)     NOT NULL,
    order_number         VARCHAR(40)    NOT NULL,
    customer_id          BINARY(16)     NOT NULL,
    status               VARCHAR(10)    NOT NULL,   -- CREATED | PENDING | CAPTURED | EXPIRED | CANCELLED
    amount               DECIMAL(12, 2) NOT NULL,
    currency             CHAR(3)        NOT NULL,
    refunded_amount      DECIMAL(12, 2) NOT NULL DEFAULT 0,
    refund_status        VARCHAR(10)    NOT NULL DEFAULT 'NONE',   -- NONE | PENDING | PARTIAL | FULL
    contact_phone        VARCHAR(32)    NULL,       -- OrderAwaitingPayment.contactPhone (Checkout prefill)
    razorpay_order_id    VARCHAR(40)    NULL,       -- set once (CREATED → PENDING)
    razorpay_payment_id  VARCHAR(40)    NULL,       -- the captured Razorpay payment
    method               VARCHAR(30)    NULL,       -- card | upi | netbanking | wallet | …
    late_capture         BOOLEAN        NOT NULL DEFAULT FALSE,   -- captured after EXPIRED / CANCELLED (refunded)
    expires_at           DATETIME(6)    NOT NULL,   -- end of the payment window
    captured_at          DATETIME(6)    NULL,
    poll_lease_until     DATETIME(6)    NULL,       -- expiry poller: claimed by a poller until then
    created_at           DATETIME(6)    NOT NULL,
    updated_at           DATETIME(6)    NOT NULL,
    version              BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_payment PRIMARY KEY (id),
    CONSTRAINT uk_payment_order UNIQUE (order_id),
    CONSTRAINT uk_payment_razorpay_order UNIQUE (razorpay_order_id),
    CONSTRAINT uk_payment_razorpay_payment UNIQUE (razorpay_payment_id),
    CONSTRAINT ck_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_refunded CHECK (refunded_amount >= 0 AND refunded_amount <= amount),
    INDEX idx_payment_status_expires (status, expires_at),
    INDEX idx_payment_created (created_at),
    INDEX idx_payment_customer_created (customer_id, created_at)
) ENGINE = InnoDB;

-- Every Razorpay payment seen for a payment's Razorpay order (declined ones included), from verify, webhooks and the
-- expiry poller. One row per Razorpay payment id.
CREATE TABLE payment_attempt (
    id                   BINARY(16)     NOT NULL,
    payment_id           BINARY(16)     NOT NULL,
    razorpay_payment_id  VARCHAR(40)    NOT NULL,
    status               VARCHAR(12)    NOT NULL,   -- AUTHORIZED | CAPTURED | FAILED
    method               VARCHAR(30)    NULL,
    amount               DECIMAL(12, 2) NULL,
    error_code           VARCHAR(60)    NULL,
    error_description    VARCHAR(500)   NULL,
    created_at           DATETIME(6)    NOT NULL,
    updated_at           DATETIME(6)    NOT NULL,
    CONSTRAINT pk_payment_attempt PRIMARY KEY (id),
    CONSTRAINT uk_payment_attempt_razorpay_payment UNIQUE (razorpay_payment_id),
    CONSTRAINT fk_payment_attempt_payment FOREIGN KEY (payment_id) REFERENCES payment (id),
    INDEX idx_payment_attempt_payment (payment_id, created_at)
) ENGINE = InnoDB;

-- Refunds of a captured payment (ORDER_CANCELLED | LATE_CAPTURE | ADMIN). PENDING ones count against the refundable
-- amount; refunded_amount on the payment grows only when a refund is PROCESSED.
CREATE TABLE refund (
    id                  BINARY(16)     NOT NULL,
    payment_id          BINARY(16)     NOT NULL,
    order_id            BINARY(16)     NOT NULL,
    amount              DECIMAL(12, 2) NOT NULL,
    reason              VARCHAR(20)    NOT NULL,   -- ORDER_CANCELLED | LATE_CAPTURE | ADMIN
    note                VARCHAR(300)   NULL,
    status              VARCHAR(10)    NOT NULL,   -- PENDING | PROCESSED | FAILED
    razorpay_refund_id  VARCHAR(40)    NULL,
    failure_code        VARCHAR(60)    NULL,
    failure_reason      VARCHAR(500)   NULL,
    requested_by        BINARY(16)     NULL,       -- admin user id (ADMIN refunds)
    provider_attempts   INT            NOT NULL DEFAULT 0,
    next_attempt_at     DATETIME(6)    NULL,       -- refund poller: when to (re)try / re-check at the provider
    lease_until         DATETIME(6)    NULL,       -- a provider call is in flight until then
    created_at          DATETIME(6)    NOT NULL,
    processed_at        DATETIME(6)    NULL,
    updated_at          DATETIME(6)    NOT NULL,
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_refund PRIMARY KEY (id),
    CONSTRAINT uk_refund_razorpay_refund UNIQUE (razorpay_refund_id),
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment (id),
    CONSTRAINT ck_refund_amount CHECK (amount > 0),
    INDEX idx_refund_payment (payment_id, created_at),
    INDEX idx_refund_status_next (status, next_attempt_at)
) ENGINE = InnoDB;

-- Every webhook with a valid signature, exactly as received (de-duplicated by Razorpay's event id).
CREATE TABLE webhook_event (
    id           BINARY(16)    NOT NULL,
    event_id     VARCHAR(100)  NOT NULL,       -- X-Razorpay-Event-Id
    event_type   VARCHAR(60)   NOT NULL,       -- payment.captured, refund.processed, …
    payload      MEDIUMTEXT    NOT NULL,       -- the raw signed body
    received_at  DATETIME(6)   NOT NULL,
    processed    BOOLEAN       NOT NULL DEFAULT FALSE,   -- TRUE = applied; FALSE = stored and ignored
    outcome      VARCHAR(200)  NULL,
    CONSTRAINT pk_webhook_event PRIMARY KEY (id),
    CONSTRAINT uk_webhook_event_event UNIQUE (event_id),
    INDEX idx_webhook_event_received (received_at)
) ENGINE = InnoDB;

-- Idempotency-Key of POST /api/admin/refunds (grocery-common IdempotencyKeys).
CREATE TABLE idempotency_key (
    scope         VARCHAR(100)  NOT NULL,
    idem_key      VARCHAR(100)  NOT NULL,
    request_hash  CHAR(64)      NOT NULL,
    result_ref    VARCHAR(100)  NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (scope, idem_key)
) ENGINE = InnoDB;
