package dev.tkachuk.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A migrated PostgreSQL for the repository tests, without Spring. */
public final class TestDatabase {

    private static final PostgreSQLContainer POSTGRES = startAndMigrate();
    private static final JdbcClient JDBC = JdbcClient.create(dataSource());

    private TestDatabase() {
    }

    public static JdbcClient jdbc() {
        return JDBC;
    }

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), driverProperties());
    }

    private static PostgreSQLContainer startAndMigrate() {
        PostgreSQLContainer postgres = new PostgreSQLContainer(TestImages.fromSystemProperty("postgres.image"));
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
        return postgres;
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl());
        dataSource.setConnectionProperties(driverProperties());
        return dataSource;
    }

    private static Properties driverProperties() {
        Properties properties = new Properties();
        properties.setProperty("user", POSTGRES.getUsername());
        properties.setProperty("password", POSTGRES.getPassword());
        // A test stuck on a row lock fails after 5 s instead of hanging.
        properties.setProperty("options", "-c statement_timeout=5000");
        return properties;
    }
}
