package com.crm.foundation.Plugin;

import com.crm.foundation.config.TestContainersConfig;
import com.crm.foundation.support.PluginPackageBuilder;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup resilience with plugin entities (design 6.1): a plugin whose
 * entities don't match its tables, or that maps a table outside its own
 * schema, is FAILED and left out of Foundation's persistence unit — the
 * application and the other plugins still start.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest
@Import(TestContainersConfig.class)
class PluginIsolationIT {

    private static final String GOOD = "com.crm.iso.good";
    private static final String BADMAP = "com.crm.iso.badmap";
    private static final String ESCAPE = "com.crm.iso.escape";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PluginBootstrap bootstrap;

    @Autowired
    private EntityManagerFactory emf;

    /** Builds the three plugin packages before the context (and its EntityManagerFactory) starts. */
    @DynamicPropertySource
    static void pluginDirectory(DynamicPropertyRegistry registry) throws IOException {
        Path pluginDir = Files.createTempDirectory("isolation-plugins-");

        PluginPackageBuilder.plugin(GOOD)
            .schema("plugin_iso_good")
            .entry("iso.good.GoodPlugin")
            .source("iso.good.GoodPlugin", activator("iso.good", "GoodPlugin"))
            .source("iso.good.Note", """
                package iso.good;

                import jakarta.persistence.*;
                import java.util.UUID;

                @Entity
                @Table(schema = "plugin_iso_good", name = "note")
                public class Note {
                    @Id
                    private UUID id;
                    @Version
                    private long version;
                    private String text;
                }
                """)
            .migration("V1__note.sql", """
                CREATE TABLE note (
                    id      UUID PRIMARY KEY,
                    version BIGINT NOT NULL,
                    text    VARCHAR(255)
                );
                """)
            .buildTo(pluginDir.resolve("good.zip"));

        PluginPackageBuilder.plugin(BADMAP)
            .schema("plugin_iso_badmap")
            .entry("iso.badmap.BadmapPlugin")
            .source("iso.badmap.BadmapPlugin", activator("iso.badmap", "BadmapPlugin"))
            .source("iso.badmap.Thing", """
                package iso.badmap;

                import jakarta.persistence.*;
                import java.util.UUID;

                @Entity
                @Table(schema = "plugin_iso_badmap", name = "thing")
                public class Thing {
                    @Id
                    private UUID id;
                    @Column(name = "missing_col")
                    private String missing;
                }
                """)
            .migration("V1__thing.sql", """
                CREATE TABLE thing (
                    id UUID PRIMARY KEY
                );
                """)
            .buildTo(pluginDir.resolve("badmap.zip"));

        PluginPackageBuilder.plugin(ESCAPE)
            .schema("plugin_iso_escape")
            .entry("iso.escape.EscapePlugin")
            .source("iso.escape.EscapePlugin", activator("iso.escape", "EscapePlugin"))
            .source("iso.escape.Intruder", """
                package iso.escape;

                import jakarta.persistence.*;
                import java.util.UUID;

                @Entity
                @Table(schema = "public", name = "users")
                public class Intruder {
                    @Id
                    private UUID id;
                    private String username;
                }
                """)
            .buildTo(pluginDir.resolve("escape.zip"));

        registry.add("foundation.plugins.directory", pluginDir::toString);
    }

    private static String activator(String pkg, String name) {
        return """
            package %s;
            public class %s implements com.crm.foundation.Plugin.PluginActivator {
                public void onLoad() {
                }
            }
            """.formatted(pkg, name);
    }

    private BootstrappedPlugin plugin(String id) {
        return bootstrap.report().plugins().stream()
            .filter(p -> p.manifest().id().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError("plugin " + id + " not in report: " + bootstrap.report()));
    }

    @Test
    void context_starts_with_broken_plugins_present() {
        assertThat(context).isNotNull();
        assertThat(bootstrap.report().plugins()).hasSize(3);
    }

    @Test
    void good_plugin_ready_others_failed() {
        assertThat(plugin(GOOD).status()).isEqualTo(BootstrappedPlugin.Status.READY);
        assertThat(plugin(GOOD).entityClassNames()).containsExactly("iso.good.Note");

        assertThat(plugin(BADMAP).status()).isEqualTo(BootstrappedPlugin.Status.FAILED);
        assertThat(plugin(BADMAP).failureReason()).contains("missing_col");
        assertThat(plugin(BADMAP).classLoader()).isNull();

        assertThat(plugin(ESCAPE).status()).isEqualTo(BootstrappedPlugin.Status.FAILED);
        assertThat(plugin(ESCAPE).failureReason()).contains("schema");
        assertThat(plugin(ESCAPE).classLoader()).isNull();
    }

    @Test
    void failed_plugins_contribute_no_entities() {
        assertThat(emf.getMetamodel().getEntities())
            .extracting(EntityType::getJavaType)
            .extracting(Class::getName)
            .filteredOn(name -> name.startsWith("iso."))
            .containsExactly("iso.good.Note");
    }
}
