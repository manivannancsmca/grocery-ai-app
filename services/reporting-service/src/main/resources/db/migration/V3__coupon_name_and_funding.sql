-- The coupon's name and who funds its discounts (PLATFORM / STORE), from OrderCreated / OrderConfirmed since
-- grocery-event-schemas 1.7.0 (order-service takes them from pricing-service when it redeems the coupon). Like the
-- other descriptive columns, the latest non-null value wins. NULL without a coupon, and for orders placed before
-- order-service stored them: their events carry neither, so a rebuild cannot fill them either.
-- Expand-only: two nullable columns (ALGORITHM=INSTANT on MySQL 8.4), and the ad-hoc view shows them too.
ALTER TABLE order_fact
    ADD COLUMN coupon_name       VARCHAR(200) NULL AFTER coupon_code,
    ADD COLUMN coupon_funded_by  VARCHAR(20)  NULL AFTER coupon_name;

CREATE OR REPLACE VIEW order_summary AS
SELECT o.order_id, o.order_number, o.order_type, o.customer_id, o.store_id, o.store_name, o.currency, o.subtotal,
       o.delivery_fee, o.discount, o.delivery_fee_discount, o.total, o.coupon_code, o.status, o.placed_at,
       o.created_at, o.confirmed_at, o.cancelled_at, o.rejected_at, o.delivered_at, o.delivery_failed_at,
       COALESCE((SELECT SUM(r.amount) FROM refund_fact r WHERE r.order_id = o.order_id), 0.00) AS refunded_amount,
       COALESCE((SELECT SUM(t.refund_amount) FROM return_fact t WHERE t.order_id = o.order_id), 0.00)
           AS returned_amount,
       o.coupon_name, o.coupon_funded_by
FROM order_fact o;
