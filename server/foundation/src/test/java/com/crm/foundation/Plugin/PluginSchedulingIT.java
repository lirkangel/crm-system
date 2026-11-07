package com.crm.foundation.Plugin;

import com.crm.foundation.config.TestContainersConfig;
import com.crm.foundation.support.PluginPackageBuilder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plugin's {@code @Scheduled} bean runs (the child context enables scheduling
 * on Foundation's shared task scheduler) and stops when the child context closes.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class) // the second test closes the plugin context the first one needs
@SpringBootTest
@Import(TestContainersConfig.class)
class PluginSchedulingIT {

    private static final String PLUGIN_ID = "com.crm.sched";
    private static final String COUNTER = "crm.it.sched.ticks";
    private static final String THREAD = "crm.it.sched.thread";

    @Autowired
    private PluginHost host;

    @DynamicPropertySource
    static void pluginDirectory(DynamicPropertyRegistry registry) throws IOException {
        Path pluginDir = Files.createTempDirectory("sched-plugins-");
        PluginPackageBuilder.plugin(PLUGIN_ID)
            .schema("plugin_sched")
            .entry("demo.sched.SchedPlugin")
            .withClasspathOf(Scheduled.class, Component.class)
            .source("demo.sched.SchedPlugin", """
                package demo.sched;
                public class SchedPlugin implements com.crm.foundation.Plugin.PluginActivator {
                    public void onLoad() {
                    }
                }
                """)
            .source("demo.sched.Ticker", """
                package demo.sched;

                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class Ticker {
                    @Scheduled(fixedDelay = 100)
                    public void tick() {
                        System.setProperty("%s", Thread.currentThread().getName());
                        synchronized (System.class) {
                            int n = Integer.parseInt(System.getProperty("%s", "0"));
                            System.setProperty("%s", String.valueOf(n + 1));
                        }
                    }
                }
                """.formatted(THREAD, COUNTER, COUNTER))
            .buildTo(pluginDir.resolve("sched.zip"));
        registry.add("foundation.plugins.directory", pluginDir::toString);
    }

    private static int ticks() {
        return Integer.parseInt(System.getProperty(COUNTER, "0"));
    }

    private static boolean awaitTicksAtLeast(int n, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ticks() >= n) {
                return true;
            }
            Thread.sleep(50);
        }
        return ticks() >= n;
    }

    @Test
    @Order(1)
    void scheduled_method_in_plugin_runs() throws InterruptedException {
        assertThat(awaitTicksAtLeast(3, 5_000)).as("ticks after 5 s: %d", ticks()).isTrue();
    }

    @Test
    @Order(1)
    void scheduled_method_runs_on_foundations_shared_scheduler() throws InterruptedException {
        assertThat(awaitTicksAtLeast(1, 5_000)).isTrue();

        assertThat(System.getProperty(THREAD)).startsWith("scheduling-");
    }

    @Test
    @Order(2)
    void scheduled_method_stops_after_child_context_closes() throws InterruptedException {
        assertThat(awaitTicksAtLeast(1, 5_000)).isTrue();
        ConfigurableApplicationContext child = host.runningContext(PLUGIN_ID)
            .orElseThrow(() -> new AssertionError("plugin " + PLUGIN_ID + " is not running"));

        child.close();
        Thread.sleep(300); // let an in-flight execution finish
        int after = ticks();
        Thread.sleep(1_000);

        assertThat(ticks()).isEqualTo(after);
    }
}
