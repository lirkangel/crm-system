package com.crm.foundation.support;

import org.testcontainers.DockerClientFactory;

/**
 * Shared predicates for tests that need the Docker API (Testcontainers).
 */
public final class DockerTestSupport {

    private DockerTestSupport() {
    }

    public static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }
}
