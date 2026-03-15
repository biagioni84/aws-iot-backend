package uy.plomo.cloud;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Container PostgreSQL compartido — se levanta una sola vez por JVM.
 * Testcontainers lo reutiliza automáticamente entre tests del mismo ApplicationContext.
 */
@TestConfiguration
public class PostgresTestConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16-alpine");
    }
}
