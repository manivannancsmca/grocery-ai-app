-- A customer's profile name and phone reach their account (customer.events CustomerProfileUpdated, consumer group
-- identity-service.customer-events): the updatedAt of the last profile applied, so an older event that arrives
-- later (a replay, a dead-letter replay) never brings back an old name. NULL = no profile event applied yet.
-- Expand-only: nullable, no default (ALGORITHM=INSTANT on MySQL 8.4).
ALTER TABLE user_account ADD COLUMN profile_updated_at DATETIME(6) NULL AFTER updated_at;
