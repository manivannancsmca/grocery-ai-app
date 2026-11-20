-- Phase 10 (docs/api/phase-10-event-driven-hardening.md §3): payment reconciliation. A job asks the provider about
-- PENDING payments whose Razorpay order is older than grocery.payment.reconcile-after and applies what it finds through
-- the same code as the webhook (lost verify call AND lost webhook → still captured).

ALTER TABLE payment
    -- when the Razorpay order was created (CREATED → PENDING); rows from before: their creation time
    ADD COLUMN provider_order_at       DATETIME(6) NULL AFTER razorpay_payment_id,
    -- reconciliation: claimed by a run until then (a provider call is in flight), and the run that last checked it
    ADD COLUMN reconcile_lease_until   DATETIME(6) NULL AFTER poll_lease_until,
    ADD COLUMN reconciled_at           DATETIME(6) NULL AFTER reconcile_lease_until;

UPDATE payment SET provider_order_at = created_at WHERE razorpay_order_id IS NOT NULL;

CREATE INDEX idx_payment_status_provider_order ON payment (status, provider_order_at);

-- The job's counters since it first ran, over every replica (one row, id = 1): GET /api/admin/payments/reconciliation.
CREATE TABLE reconciliation_stats (
    id           TINYINT      NOT NULL,
    last_run_at  DATETIME(6)  NULL,       -- when a run (of any replica) last finished
    checked      BIGINT       NOT NULL DEFAULT 0,   -- payments asked about at the provider
    recovered    BIGINT       NOT NULL DEFAULT 0,   -- payments brought in line (a capture or a decline we had missed)
    failures     BIGINT       NOT NULL DEFAULT 0,   -- checks that failed (provider unreachable, …), retried next run
    CONSTRAINT pk_reconciliation_stats PRIMARY KEY (id)
) ENGINE = InnoDB;

INSERT INTO reconciliation_stats (id) VALUES (1);

-- What the job recovered, newest last (the API shows the last 20). Durable, so it survives restarts and replicas.
CREATE TABLE reconciliation_recovery (
    id                   BINARY(16)   NOT NULL,
    payment_id           BINARY(16)   NOT NULL,
    order_id             BINARY(16)   NOT NULL,
    order_number         VARCHAR(40)  NOT NULL,
    outcome              VARCHAR(20)  NOT NULL,   -- CAPTURED | AUTHORIZED (captured by us) | FAILED
    razorpay_payment_id  VARCHAR(40)  NULL,
    recovered_at         DATETIME(6)  NOT NULL,
    CONSTRAINT pk_reconciliation_recovery PRIMARY KEY (id),
    INDEX idx_reconciliation_recovery_at (recovered_at)
) ENGINE = InnoDB;
