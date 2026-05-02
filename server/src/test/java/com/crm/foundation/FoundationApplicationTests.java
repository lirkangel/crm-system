package com.crm.foundation;

import com.crm.foundation.config.TestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Context-load smoke test.
 * Verifies the Spring application context starts clean against a real Postgres instance.
 *
 * Run: mvn -pl server test -Dtest=FoundationApplicationTests
 */
@SpringBootTest
@Import(TestContainersConfig.class)
class FoundationApplicationTests {

    @Test
    void contextLoads() {
        // passes if the application context starts without errors
    }
}
