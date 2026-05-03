package com.crm.foundation;

import com.crm.foundation.config.TestContainersConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.DockerClientFactory;

/**
 * Context-load smoke test (Docker + Postgres via Testcontainers).
 * Excluded from default {@code mvn test}; run with {@code mvn verify -Pintegration}.
 * <p>
 * Skipped when no Docker API is visible to the JVM (CI without DinD, IDE runs without socket, Colima path, etc.).
 * If Docker is running but this still skips, set {@code DOCKER_HOST} (see {@link com.crm.foundation.config.TestContainersConfig}).
 */
@Tag("integration")
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker not available for Testcontainers")
@SpringBootTest
@Import(TestContainersConfig.class)
class FoundationApplicationTests {

    @SuppressWarnings("unused")
    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @Test
    void contextLoads() {
        // passes if the application context starts without errors
    }
}
