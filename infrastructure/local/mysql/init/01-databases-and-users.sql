-- =====================================================================
-- Local development only. Runs once, when the MySQL volume is empty.
-- ONE MySQL container hosts every service database (memory optimisation),
-- but each service gets its OWN user that can only see its OWN database,
-- so cross-service database access is impossible even on a laptop.
-- Passwords here are local-dev defaults; production uses Secrets Manager.
-- =====================================================================

-- ---------- Databases (one per service that persists in MySQL) ----------
CREATE DATABASE IF NOT EXISTS identity_db     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS customer_db     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS vendor_db       CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS restaurant_db   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS catalog_db      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS inventory_db    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS pricing_db      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS order_db        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS payment_db      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS delivery_db     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS notification_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS review_db       CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS ai_db           CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- ---------- One least-privilege user per service ----------
CREATE USER IF NOT EXISTS 'identity_user'@'%'     IDENTIFIED BY 'identity_pass';
CREATE USER IF NOT EXISTS 'customer_user'@'%'     IDENTIFIED BY 'customer_pass';
CREATE USER IF NOT EXISTS 'vendor_user'@'%'       IDENTIFIED BY 'vendor_pass';
CREATE USER IF NOT EXISTS 'restaurant_user'@'%'   IDENTIFIED BY 'restaurant_pass';
CREATE USER IF NOT EXISTS 'catalog_user'@'%'      IDENTIFIED BY 'catalog_pass';
CREATE USER IF NOT EXISTS 'inventory_user'@'%'    IDENTIFIED BY 'inventory_pass';
CREATE USER IF NOT EXISTS 'pricing_user'@'%'      IDENTIFIED BY 'pricing_pass';
CREATE USER IF NOT EXISTS 'order_user'@'%'        IDENTIFIED BY 'order_pass';
CREATE USER IF NOT EXISTS 'payment_user'@'%'      IDENTIFIED BY 'payment_pass';
CREATE USER IF NOT EXISTS 'delivery_user'@'%'     IDENTIFIED BY 'delivery_pass';
CREATE USER IF NOT EXISTS 'notification_user'@'%' IDENTIFIED BY 'notification_pass';
CREATE USER IF NOT EXISTS 'review_user'@'%'       IDENTIFIED BY 'review_pass';
CREATE USER IF NOT EXISTS 'ai_user'@'%'           IDENTIFIED BY 'ai_pass';

GRANT ALL PRIVILEGES ON identity_db.*     TO 'identity_user'@'%';
GRANT ALL PRIVILEGES ON customer_db.*     TO 'customer_user'@'%';
GRANT ALL PRIVILEGES ON vendor_db.*       TO 'vendor_user'@'%';
GRANT ALL PRIVILEGES ON restaurant_db.*   TO 'restaurant_user'@'%';
GRANT ALL PRIVILEGES ON catalog_db.*      TO 'catalog_user'@'%';
GRANT ALL PRIVILEGES ON inventory_db.*    TO 'inventory_user'@'%';
GRANT ALL PRIVILEGES ON pricing_db.*      TO 'pricing_user'@'%';
GRANT ALL PRIVILEGES ON order_db.*        TO 'order_user'@'%';
GRANT ALL PRIVILEGES ON payment_db.*      TO 'payment_user'@'%';
GRANT ALL PRIVILEGES ON delivery_db.*     TO 'delivery_user'@'%';
GRANT ALL PRIVILEGES ON notification_db.* TO 'notification_user'@'%';
GRANT ALL PRIVILEGES ON review_db.*       TO 'review_user'@'%';
GRANT ALL PRIVILEGES ON ai_db.*           TO 'ai_user'@'%';

-- ---------- Debezium (outbox relay) ----------
-- Global privileges required by the MySQL binlog connector; read-only (SELECT)
-- on service databases so it can read the outbox table structure.
CREATE USER IF NOT EXISTS 'debezium'@'%' IDENTIFIED BY 'debezium_pass';
GRANT RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';
GRANT SELECT ON identity_db.*     TO 'debezium'@'%';
GRANT SELECT ON customer_db.*     TO 'debezium'@'%';
GRANT SELECT ON vendor_db.*       TO 'debezium'@'%';
GRANT SELECT ON restaurant_db.*   TO 'debezium'@'%';
GRANT SELECT ON catalog_db.*      TO 'debezium'@'%';
GRANT SELECT ON inventory_db.*    TO 'debezium'@'%';
GRANT SELECT ON pricing_db.*      TO 'debezium'@'%';
GRANT SELECT ON order_db.*        TO 'debezium'@'%';
GRANT SELECT ON payment_db.*      TO 'debezium'@'%';
GRANT SELECT ON delivery_db.*     TO 'debezium'@'%';
GRANT SELECT ON notification_db.* TO 'debezium'@'%';
GRANT SELECT ON review_db.*       TO 'debezium'@'%';
GRANT SELECT ON ai_db.*           TO 'debezium'@'%';

FLUSH PRIVILEGES;
