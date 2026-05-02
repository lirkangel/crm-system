package com.crm.foundation.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers config — import into any {@code @SpringBootTest} that needs Postgres.
 * <p>
 * If you see {@code Could not find a valid Docker environment} even with Docker running:
 * <ul>
 *   <li>Run Maven from the same terminal where {@code docker info} works (IDE runs sometimes strip env).</li>
 *   <li>Docker Desktop (Mac): Settings → Advanced → enable using the default Docker socket.</li>
 *   <li>Colima: {@code export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"} before {@code mvn}.</li>
 *   <li>Try {@code mvn verify -Pintegration} (Surefire uses {@code forkCount=0} so the test JVM matches Maven).</li>
 * </ul>
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
