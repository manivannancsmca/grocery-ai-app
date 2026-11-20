package com.grocery.dbtests;

import com.grocery.dbtests.ServiceMigrations.Service;
import com.grocery.dbtests.ServiceMigrations.Version;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One MySQL 8.4.13 container for the whole run (started on first use, stopped by Testcontainers' reaper), configured
 * like the local stack's server (infrastructure/local/docker-compose.yml: GTID consistency enforced, utf8mb4 /
 * utf8mb4_0900_ai_ci, UTC), with one fresh database per test, created like the stack's init script does.
 */
final class MySql {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static volatile MySQLContainer container;

    private MySql() {
    }

    static MySQLContainer container() {
        MySQLContainer started = container;
        if (started == null) {
            synchronized (MySql.class) {
                started = container;
                if (started == null) {
                    started = new MySQLContainer(System.getProperty("mysql.image", "mysql:8.4.13"))
                            .withUsername("root")
                            .withPassword("db-tests")
                            .withCommand("--server-id=1", "--gtid-mode=ON", "--enforce-gtid-consistency=ON",
                                    "--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
                                    "--default-time-zone=+00:00", "--skip-name-resolve");
                    started.start();
                    container = started;
                }
            }
        }
        return started;
    }

    /** A new, empty database for the service (name {@code <service>_<n>}), as the stack's init script creates it. */
    static String createDatabase(Service service) {
        String name = service.database() + "_" + DATABASES.incrementAndGet();
        execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        return name;
    }

    static String url(String database) {
        MySQLContainer mysql = container();
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
    }

    static Connection connect(String database) throws SQLException {
        MySQLContainer mysql = container();
        return DriverManager.getConnection(url(database), mysql.getUsername(), mysql.getPassword());
    }

    static void execute(String sql) {
        try (Connection connection = connect("mysql"); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /**
     * Flyway as the services run it: Spring Boot's defaults (spring.flyway.* is not customised by any service: no
     * baseline-on-migrate, validate-on-migrate, out-of-order off, table flyway_schema_history), only the location
     * points at the source tree instead of classpath:db/migration.
     */
    static Flyway flyway(Service service, String database, Version target) {
        MySQLContainer mysql = container();
        var configuration = Flyway.configure()
                .dataSource(url(database), mysql.getUsername(), mysql.getPassword())
                .locations(service.location())
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .failOnMissingLocations(true)
                .cleanDisabled(true);
        if (target != null) {
            configuration = configuration.target(target.toString());
        }
        return configuration.load();
    }

    static MigrateResult migrate(Service service, String database, Version target) {
        return flyway(service, database, target).migrate();
    }
}
