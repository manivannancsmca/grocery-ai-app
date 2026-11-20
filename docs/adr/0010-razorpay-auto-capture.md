# ADR-0010: Razorpay with auto-capture for the MVP

- **Status:** Accepted
- **Date:** 2026-11-10

## Context

The platform takes online payments in INR. Razorpay is the chosen gateway, used in test mode for the MVP.
Orders can still be rejected after payment (restaurant rejects, vendor rejects, acceptance timeout), so refunds
must be reliable. Authorize-then-capture would avoid refunds for rejected orders, but support differs by payment method.

## Decision

- **Flow:**
  1. payment-service consumes `OrderAwaitingPayment` and creates a **Razorpay Order** (amount in paise,
     `receipt = orderId`). It is idempotent through a unique `order_id`.
  2. The SPA opens **Razorpay Checkout** with that order ID.
  3. On success the SPA posts the payment ID, order ID and signature to `/api/payments/razorpay/verify`.
     The HMAC-SHA256 signature is verified with the key secret.
  4. **Webhooks** (`payment.captured`, `payment.failed`, `refund.processed`) are the **source of truth**.
     They are verified with `X-Razorpay-Signature` and de-duplicated by the Razorpay event ID.
- **Auto-capture** for the MVP. Any cancellation after capture triggers a refund through the Refunds API.
  A capture that arrives after cancellation is refunded automatically.
- Payment window of 15 minutes, then `PaymentExpired`. A reconciliation job (Phase 10) queries Razorpay for payments stuck pending.
- Card data never reaches our servers. The key secret lives only in payment-service. Money is stored as
  `DECIMAL(12,2)` in INR and converted to paise only at the gateway boundary.

## Consequences

- ✅ Simplest correct integration, and it behaves the same for cards, UPI and wallets.
- ✅ Webhooks plus reconciliation tolerate lost browser callbacks and delayed webhooks.
- ⚠️ Rejected orders are refunded, not voided. Refunds can take bank processing days.
  Instant refunds (paid feature) or manual capture can be evaluated later.
- ⚠️ GST and tax display rules still need a business decision (whether prices include tax).

## Refinements (Phase 6, as built)

- **Receipt:** `receipt` is the order number (short and readable for support). `notes.orderId` carries the order UUID.
- **Verify does not trust the browser.** After the signature check, payment-service fetches the payment from Razorpay.
  An `authorized` payment is captured explicitly, which covers accounts where auto-capture is off or delayed. The
  `payment.authorized` webhook does the same.
- **Reconciliation started early.** Before expiring a payment, the payment-window poller asks Razorpay for the
  order's payments, so a lost webhook never cancels a paid order. A refund poller also re-checks pending refunds.
- **Local payment simulator.** It is a development aid, not an alternative provider. Without test keys,
  payment-service uses an in-memory stand-in that implements the same provider interface, so verify, webhooks,
  expiry, late capture and refunds run the same code. It refuses to start outside the `local` / `docker` profiles.
- **GST:** prices are inclusive of GST (decided in Phase 5).

## Alternatives considered

- **Manual capture** (authorize, then capture on acceptance): better UX for rejections, but the capture window and
  per-method support need verification. Revisit after the MVP.
- **Stripe / simulated provider:** not chosen. Razorpay was selected by the product owner.
