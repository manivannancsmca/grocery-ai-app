-- The redeemed coupon's name and who funds its discounts, as pricing-service answered the redemption (schemas 1.7.0):
-- coupon_funded_by = PLATFORM (a platform coupon) or STORE (the store's own coupon). Only published in OrderCreated /
-- OrderConfirmed, so reporting-service can name the coupon and split the discounts by who pays for them.
-- NULL without a coupon, and for every order placed before this migration (nothing is backfilled).
-- Expand-only: two nullable columns, no default (ALGORITHM=INSTANT on MySQL 8.4).
ALTER TABLE customer_order
    ADD COLUMN coupon_name       VARCHAR(80) NULL AFTER coupon_redemption_id,
    ADD COLUMN coupon_funded_by  VARCHAR(10) NULL AFTER coupon_name;
