-- =====================================================================
-- Local development only. Phase 12: reporting-service's database and user.
-- Idempotent (IF NOT EXISTS everywhere), so it is safe to run on every start:
--   * on a fresh volume MySQL runs it once from /docker-entrypoint-initdb.d (after 01-databases-and-users.sql);
--   * on an existing volume the one-shot "mysql-init" compose service applies it.
-- Least privilege as in 01: reporting_user sees only reporting_db. reporting-service publishes no events, so it has
-- no outbox, no Debezium grant and no connector.
-- Passwords here are local-dev defaults; production uses Secrets Manager.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS reporting_db    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'reporting_user'@'%'    IDENTIFIED BY 'reporting_pass';

GRANT ALL PRIVILEGES ON reporting_db.*    TO 'reporting_user'@'%';

FLUSH PRIVILEGES;
