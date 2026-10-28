package org.archive.sitearchive;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL in a container, with the same image as {@code scripts/start-db.sh}, for tests that need the real
 * database. Spring points the datasource at it and Liquibase creates the schema. Test classes with the same context
 * configuration share the cached context, and so the container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17");
    }
}
