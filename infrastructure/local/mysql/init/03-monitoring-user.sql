-- =====================================================================
-- Local development only. Phase 13: read-only user for mysqld-exporter (compose profile "observability").
-- Idempotent (IF NOT EXISTS, GRANT), so it is safe to run on every start:
--   * on a fresh volume MySQL runs it once from /docker-entrypoint-initdb.d (after 01 and 02);
--   * on an existing volume the one-shot "mysql-init" compose service applies it.
-- Least privilege: server status, variables, processlist and binlog/replication status only. No SELECT on any
-- service database, so the exporter can never read business data. At most 3 connections.
-- The password is a local-dev default (compose: MYSQL_MONITORING_PASSWORD); production uses Secrets Manager.
-- =====================================================================

CREATE USER IF NOT EXISTS 'monitoring_user'@'%' IDENTIFIED BY 'monitoring_pass' WITH MAX_USER_CONNECTIONS 3;

GRANT PROCESS, REPLICATION CLIENT ON *.* TO 'monitoring_user'@'%';

FLUSH PRIVILEGES;
