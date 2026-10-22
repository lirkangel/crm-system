package com.crm.foundation.Plugin;

import com.crm.foundation.Service.ChangeEventService;
import com.crm.foundation.config.TestContainersConfig;
import com.crm.foundation.support.PluginPackageBuilder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Plugin JPA entities live in Foundation's own persistence unit (design 6.1):
 * a plugin entity is managed by Foundation's {@link EntityManagerFactory}, and
 * a plugin write commits or rolls back together with its {@code change_event}.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest
@Import(TestContainersConfig.class)
class PluginJpaIT {

    private static final String PLUGIN_ID = "com.crm.demo.jpa";
    private static final String NOTE_CLASS = "demo.jpa.Note";

    @Autowired
    private PluginBootstrap bootstrap;

    @Autowired
    private EntityManagerFactory emf;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ChangeEventService changeEventService;

    @Autowired
    private JdbcTemplate jdbc;

    /** Builds the plugin package before the context (and its EntityManagerFactory) starts. */
    @DynamicPropertySource
    static void pluginDirectory(DynamicPropertyRegistry registry) throws IOException {
        Path pluginDir = Files.createTempDirectory("jpa-plugins-");
        PluginPackageBuilder.plugin(PLUGIN_ID)
            .schema("plugin_jpa")
            .entry("demo.jpa.JpaDemoPlugin")
            .source("demo.jpa.JpaDemoPlugin", """
                package demo.jpa;
                public class JpaDemoPlugin implements com.crm.foundation.Plugin.PluginActivator {
                    public void onLoad() {
                    }
                }
                """)
            .source(NOTE_CLASS, """
                package demo.jpa;

                import jakarta.persistence.*;
                import java.util.UUID;

                @Entity
                @Table(schema = "plugin_jpa", name = "note")
                public class Note {
                    @Id
                    private UUID id;
                    @Version
                    private long version;
                    private String text;

                    public Note() {
                    }

                    public Note(UUID id, String text) {
                        this.id = id;
                        this.text = text;
                    }
                }
                """)
            .migration("V1__note.sql", """
                CREATE TABLE note (
                    id      UUID PRIMARY KEY,
                    version BIGINT NOT NULL,
                    text    VARCHAR(255)
                );
                """)
            .buildTo(pluginDir.resolve("jpa-demo.zip"));
        registry.add("foundation.plugins.directory", pluginDir::toString);
    }

    private Class<?> noteClass() throws ClassNotFoundException {
        BootstrappedPlugin plugin = bootstrap.report().ready().stream()
            .filter(p -> p.manifest().id().equals(PLUGIN_ID))
            .findFirst()
            .orElseThrow(() -> new AssertionError("plugin " + PLUGIN_ID + " not READY: " + bootstrap.report()));
        assertThat(plugin.entityClassNames()).containsExactly(NOTE_CLASS);
        return plugin.classLoader().loadClass(NOTE_CLASS);
    }

    private Object newNote(UUID id) throws ReflectiveOperationException {
        return noteClass().getConstructor(UUID.class, String.class).newInstance(id, "hello");
    }

    @Test
    void plugin_entity_is_managed_by_foundation_emf() throws ClassNotFoundException {
        Class<?> noteClass = noteClass();

        assertThatCode(() -> emf.getMetamodel().entity(noteClass)).doesNotThrowAnyException();
        assertThat(emf.getMetamodel().entity(noteClass).getJavaType()).isSameAs(noteClass);
    }

    @Test
    void plugin_entity_and_change_event_commit_or_roll_back_together() throws ReflectiveOperationException {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        UUID rolledBack = UUID.randomUUID();
        Object rolledBackNote = newNote(rolledBack);
        tx.executeWithoutResult(status -> {
            entityManager.persist(rolledBackNote);
            changeEventService.record("demo.jpa", "Note", rolledBack, 0, "CREATE", Instant.now());
            entityManager.flush();
            status.setRollbackOnly();
        });
        assertThat(noteRows(rolledBack)).isZero();
        assertThat(changeEventRows(rolledBack)).isZero();

        UUID committed = UUID.randomUUID();
        Object committedNote = newNote(committed);
        tx.executeWithoutResult(status -> {
            entityManager.persist(committedNote);
            changeEventService.record("demo.jpa", "Note", committed, 0, "CREATE", Instant.now());
        });
        assertThat(noteRows(committed)).isOne();
        assertThat(changeEventRows(committed)).isOne();
    }

    private int noteRows(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM plugin_jpa.note WHERE id = ?", Integer.class, id);
    }

    private int changeEventRows(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM change_event WHERE entity_id = ?", Integer.class, id);
    }
}
