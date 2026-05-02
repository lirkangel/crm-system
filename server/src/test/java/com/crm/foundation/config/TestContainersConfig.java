package com.crm.foundation.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers config — import into any @SpringBootTest that needs a real Postgres.
 *
 * Usage:
 *   @SpringBootTest
 *   @Import(TestContainersConfig.class)
 *   class MyIT { ... }
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("foundation_test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true);   // reuse running container across test classes (faster local runs)
    }
}
