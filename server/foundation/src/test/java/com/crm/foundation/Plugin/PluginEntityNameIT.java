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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA entity names are global to Foundation's persistence unit: a plugin
 * entity whose name is already taken — by a Foundation entity or by a plugin
 * bootstrapped earlier — would fail the shared {@code EntityManagerFactory}.
 * Such a plugin is FAILED instead; the first claimant keeps the name.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest
@Import(TestContainersConfig.class)
class PluginEntityNameIT {

    private static final String NOTE_A = "com.crm.clash.notea";
    private static final String NOTE_B = "com.crm.clash.noteb";
    private static final String USER = "com.crm.clash.user";

    @Autowired
    private PluginBootstrap bootstrap;

    @Autowired
    private EntityManagerFactory emf;

    /** Discovery order is by file name: a-note.zip before b-note.zip. */
    @DynamicPropertySource
    static void pluginDirectory(DynamicPropertyRegistry registry) throws IOException {
        Path pluginDir = Files.createTempDirectory("entity-name-plugins-");
        notePlugin(NOTE_A, "notea", "plugin_clash_notea").buildTo(pluginDir.resolve("a-note.zip"));
        notePlugin(NOTE_B, "noteb", "plugin_clash_noteb").buildTo(pluginDir.resolve("b-note.zip"));
        PluginPackageBuilder.plugin(USER)
            .schema("plugin_clash_user")
            .entry("clash.user.UserPlugin")
            .source("clash.user.UserPlugin", activator("clash.user", "UserPlugin"))
            .source("clash.user.User", """
                package clash.user;

                import jakarta.persistence.*;
                import java.util.UUID;

                @Entity
                @Table(schema = "plugin_clash_user", name = "app_user")
                public class User {
                    @Id
                    private UUID id;
                }
                """)
            .migration("V1__app_user.sql", """
                CREATE TABLE app_user (
                    id UUID PRIMARY KEY
                );
                """)
            .buildTo(pluginDir.resolve("user.zip"));
        registry.add("foundation.plugins.directory", pluginDir::toString);
    }

    private static PluginPackageBuilder notePlugin(String id, String pkg, String schema) {
        return PluginPackageBuilder.plugin(id)
            .schema(schema)
            .entry("clash." + pkg + ".NotePlugin")
            .source("clash." + pkg + ".NotePlugin", activator("clash." + pkg, "NotePlugin"))
            .source("clash." + pkg + ".Note", """
                package clash.%s;

                import jakarta.persistence.*;
                import java.util.UUID;

                @Entity
                @Table(schema = "%s", name = "note")
                public class Note {
                    @Id
                    private UUID id;
                }
                """.formatted(pkg, schema))
            .migration("V1__note.sql", """
                CREATE TABLE note (
                    id UUID PRIMARY KEY
                );
                """);
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
    void context_starts_with_clashing_plugins_present() {
        assertThat(bootstrap.report().plugins()).hasSize(3);
    }

    @Test
    void first_note_plugin_ready_second_failed() {
        assertThat(plugin(NOTE_A).status()).isEqualTo(BootstrappedPlugin.Status.READY);

        assertThat(plugin(NOTE_B).status()).isEqualTo(BootstrappedPlugin.Status.FAILED);
        assertThat(plugin(NOTE_B).failureReason()).isEqualTo(
            "plugin com.crm.clash.noteb entity name 'Note' (clash.noteb.Note) is already taken by "
                + "plugin com.crm.clash.notea entity clash.notea.Note");
        assertThat(plugin(NOTE_B).classLoader()).isNull();
    }

    @Test
    void plugin_entity_named_like_a_foundation_entity_failed() {
        assertThat(plugin(USER).status()).isEqualTo(BootstrappedPlugin.Status.FAILED);
        assertThat(plugin(USER).failureReason()).isEqualTo(
            "plugin com.crm.clash.user entity name 'User' (clash.user.User) is already taken by "
                + "Foundation entity com.crm.foundation.Domain.User");
        assertThat(plugin(USER).classLoader()).isNull();
    }

    @Test
    void only_the_first_note_plugin_is_in_the_metamodel() {
        assertThat(emf.getMetamodel().getEntities())
            .extracting(EntityType::getJavaType)
            .extracting(Class::getName)
            .filteredOn(name -> name.startsWith("clash."))
            .containsExactly("clash.notea.Note");
    }
}
