package com.crm.foundation;

import com.crm.foundation.config.TestContainersConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Context-load smoke test (Docker + Postgres via Testcontainers).
 * Excluded from default {@code mvn test}; run with {@code mvn verify -Pintegration}.
 * <p>
 * Requires a Docker API reachable from the JVM (see {@link com.crm.foundation.config.TestContainersConfig}).
 */
@Tag("integration")
@SpringBootTest
@Import(TestContainersConfig.class)
class FoundationApplicationTests {

    @Test
    void contextLoads() {
        // passes if the application context starts without errors
    }
}
