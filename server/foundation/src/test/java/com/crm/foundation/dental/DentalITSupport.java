package com.crm.foundation.dental;

import com.crm.foundation.config.TestContainersConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base of the dental ITs: boots the full application on Testcontainers Postgres with the real packaged hotel and
 * dental ZIPs (copied to {@code target/test-plugins-dental} by the build) as the only installed plugins.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestContainersConfig.class)
public abstract class DentalITSupport {

    protected static final String DENTAL_PLUGIN_ID = "com.crm.dental";
    protected static final String DENTAL_SCHEMA = "dental";
    protected static final String HOTEL_PLUGIN_ID = "com.crm.hotel";

    @Autowired
    protected JdbcTemplate jdbc;

    /** Absolute {@code <module>/target/test-plugins-dental}, derived from this class's own location. */
    protected static Path dentalPluginDirectory() {
        try {
            Path testClasses = Path.of(DentalITSupport.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = testClasses.getParent().resolve("test-plugins-dental").toAbsolutePath();
            try (Stream<Path> files = Files.list(dir)) {
                List<String> names = files.map(path -> path.getFileName().toString()).sorted().toList();
                assertThat(names).as("only hotel and dental belong in " + dir)
                    .containsExactly("dental-plugin.zip", "hotel-plugin.zip");
            }
            return dir;
        } catch (URISyntaxException | IOException e) {
            throw new IllegalStateException("dental plugin directory target/test-plugins-dental is not usable", e);
        }
    }
}
