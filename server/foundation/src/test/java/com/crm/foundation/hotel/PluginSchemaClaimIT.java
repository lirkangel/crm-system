package com.crm.foundation.hotel;

import com.crm.foundation.Domain.PluginRegistry;
import com.crm.foundation.Plugin.BootstrappedPlugin;
import com.crm.foundation.Plugin.PluginBootstrap;
import com.crm.foundation.Plugin.PluginDiscovery;
import com.crm.foundation.Plugin.PluginDiscoveryResult;
import com.crm.foundation.Plugin.PluginEntityScanner;
import com.crm.foundation.Plugin.PluginEntityValidator;
import com.crm.foundation.Plugin.PluginLoader;
import com.crm.foundation.Plugin.PluginManifestParser;
import com.crm.foundation.Plugin.PluginMigrator;
import com.crm.foundation.Plugin.PluginRouteRegistry;
import com.crm.foundation.Plugin.PluginState;
import com.crm.foundation.Plugin.PluginZipValidator;
import com.crm.foundation.Service.PluginRegistryService;
import com.crm.foundation.config.TestContainersConfig;
import com.crm.foundation.support.PluginPackageBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plugin's manifest {@code schema} is its own (design 6.1, schema isolation):
 * {@code public}/Foundation's schema is refused at discovery, and a second plugin
 * declaring a schema another plugin already uses — earlier in discovery order or
 * in {@code plugin_registry} — is FAILED before it is classloaded or migrated.
 * The real hotel plugin starts unaffected.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest
@Import(TestContainersConfig.class)
class PluginSchemaClaimIT {

    private static final String HOTEL_PLUGIN_ID = "com.crm.hotel";
    private static final String SQUATTER = "com.crm.iso.squatter";
    private static final String PUBLIC = "com.crm.iso.publicschema";

    private static Path pluginDir;

    @Autowired
    private PluginBootstrap bootstrap;

    @Autowired
    private PluginRegistryService registryService;

    @Autowired
    private PluginRouteRegistry routes;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PluginZipValidator zipValidator;

    @Autowired
    private PluginManifestParser manifestParser;

    @Autowired
    private PluginLoader loader;

    @Autowired
    private PluginMigrator migrator;

    @Autowired
    private PluginEntityScanner entityScanner;

    @Autowired
    private PluginEntityValidator entityValidator;

    /** hotel-plugin.zip, then (discovery order is by file name) a squatter on schema hotel; plus a public one. */
    @DynamicPropertySource
    static void pluginDirectory(DynamicPropertyRegistry registry) throws IOException {
        pluginDir = Files.createTempDirectory("schema-claim-plugins-");
        Files.copy(HotelITSupport.hotelPluginDirectory().resolve("hotel-plugin.zip"),
            pluginDir.resolve("hotel-plugin.zip"));
        squatter().buildTo(pluginDir.resolve("zz-squatter.zip"));

        PluginPackageBuilder.plugin(PUBLIC)
            .schema("public")
            .entry("iso.pub.PublicPlugin")
            .source("iso.pub.PublicPlugin", activator("iso.pub", "PublicPlugin"))
            .source("iso.pub.Intruder", """
                package iso.pub;

                import jakarta.persistence.*;
                import java.util.UUID;

                @Entity(name = "PublicIntruder")
                @Table(schema = "public", name = "users")
                public class Intruder {
                    @Id
                    private UUID id;
                    private String username;
                }
                """)
            .buildTo(pluginDir.resolve("public-schema.zip"));

        registry.add("foundation.plugins.directory", pluginDir::toString);
    }

    /** Declares hotel's schema; if it were ever migrated, {@code hotel.squatter_note} would appear. */
    private static PluginPackageBuilder squatter() {
        return PluginPackageBuilder.plugin(SQUATTER)
            .schema("hotel")
            .entry("iso.squat.SquatPlugin")
            .source("iso.squat.SquatPlugin", activator("iso.squat", "SquatPlugin"))
            .source("iso.squat.SquatterNote", """
                package iso.squat;

                import jakarta.persistence.*;
                import java.util.UUID;

                @Entity
                @Table(schema = "hotel", name = "squatter_note")
                public class SquatterNote {
                    @Id
                    private UUID id;
                }
                """)
            .migration("V900__squatter_note.sql", "CREATE TABLE squatter_note (id UUID PRIMARY KEY);");
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

    private static BootstrappedPlugin plugin(List<BootstrappedPlugin> plugins, String id) {
        return plugins.stream()
            .filter(p -> p.manifest().id().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError("plugin " + id + " not in report: " + plugins));
    }

    private int squatterTables() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'hotel' AND table_name = 'squatter_note'",
            Integer.class);
    }

    @Test
    void hotel_starts_unaffected() {
        assertThat(plugin(bootstrap.report().plugins(), HOTEL_PLUGIN_ID).status())
            .isEqualTo(BootstrappedPlugin.Status.READY);
        PluginRegistry hotel = registryService.findByPluginId(HOTEL_PLUGIN_ID).orElseThrow();
        assertThat(hotel.getState()).as(hotel.getErrorMessage()).isEqualTo(PluginState.ACTIVE.name());
        assertThat(hotel.getSchemaName()).isEqualTo("hotel");
        assertThat(routes.getHandlerMethods().keySet().toString()).contains("/api/v1/plugins/hotel/room-types");
    }

    @Test
    void public_schema_plugin_is_rejected_at_discovery() {
        PluginDiscoveryResult rejected = new PluginDiscovery(pluginDir.toString(), zipValidator, manifestParser)
            .scan().stream()
            .filter(result -> result.packagePath().endsWith("public-schema.zip"))
            .findFirst().orElseThrow();

        assertThat(rejected.isValid()).isFalse();
        assertThat(rejected.rejectionReason()).contains("schema 'public'").contains("reserved");
        // never bootstrapped, so never registered and nothing of it is mapped
        assertThat(bootstrap.report().plugins()).extracting(p -> p.manifest().id()).doesNotContain(PUBLIC);
        assertThat(registryService.findByPluginId(PUBLIC)).isEmpty();
    }

    @Test
    void later_plugin_on_an_already_claimed_schema_is_failed_and_never_migrated() {
        BootstrappedPlugin squatter = plugin(bootstrap.report().plugins(), SQUATTER);

        assertThat(squatter.status()).isEqualTo(BootstrappedPlugin.Status.FAILED);
        assertThat(squatter.failureReason())
            .startsWith("plugin " + SQUATTER + " declares schema 'hotel', which plugin " + HOTEL_PLUGIN_ID)
            .endsWith("already uses");
        assertThat(squatterTables()).isZero();
        // No LOAD_FAILED row: plugin_registry.schema_name is UNIQUE, so a row for the squatter would
        // need hotel's schema name; the failure is logged instead (see PluginHost)
        assertThat(registryService.findByPluginId(SQUATTER)).isEmpty();
    }

    @Test
    void schema_held_in_the_registry_by_another_plugin_fails_before_migration() throws IOException {
        // A later restart where only the squatter is new: hotel's registry row holds schema hotel
        Path laterDir = Files.createTempDirectory("schema-claim-later-");
        squatter().buildTo(laterDir.resolve("a-squatter.zip"));
        PluginBootstrap later = new PluginBootstrap(
            context.getBeanProvider(FlywayMigrationInitializer.class),
            context.getBeanProvider(PersistenceManagedTypes.class),
            new PluginDiscovery(laterDir.toString(), zipValidator, manifestParser),
            loader, migrator, entityScanner, entityValidator, jdbc);

        BootstrappedPlugin squatter = plugin(later.report().plugins(), SQUATTER);

        assertThat(squatter.status()).isEqualTo(BootstrappedPlugin.Status.FAILED);
        assertThat(squatter.failureReason()).isEqualTo(
            "plugin " + SQUATTER + " declares schema 'hotel', which plugin_registry assigns to plugin "
                + HOTEL_PLUGIN_ID);
        assertThat(squatterTables()).isZero();
    }

    @Test
    void registered_owner_wins_when_a_squatter_sorts_first() throws IOException {
        // A restart where a stray squatter ZIP sorts before hotel's: hotel's registry row still decides
        Path laterDir = Files.createTempDirectory("schema-claim-sorted-first-");
        squatter().buildTo(laterDir.resolve("a-squatter.zip"));
        Files.copy(HotelITSupport.hotelPluginDirectory().resolve("hotel-plugin.zip"),
            laterDir.resolve("hotel-plugin.zip"));
        PluginBootstrap later = new PluginBootstrap(
            context.getBeanProvider(FlywayMigrationInitializer.class),
            context.getBeanProvider(PersistenceManagedTypes.class),
            new PluginDiscovery(laterDir.toString(), zipValidator, manifestParser),
            loader, migrator, entityScanner, entityValidator, jdbc);

        List<BootstrappedPlugin> plugins = later.report().plugins();
        try {
            assertThat(plugins).extracting(p -> p.manifest().id()).containsExactly(SQUATTER, HOTEL_PLUGIN_ID);
            BootstrappedPlugin squatter = plugin(plugins, SQUATTER);
            assertThat(squatter.status()).isEqualTo(BootstrappedPlugin.Status.FAILED);
            assertThat(squatter.failureReason()).isEqualTo(
                "plugin " + SQUATTER + " declares schema 'hotel', which plugin_registry assigns to plugin "
                    + HOTEL_PLUGIN_ID);
            BootstrappedPlugin hotel = plugin(plugins, HOTEL_PLUGIN_ID);
            assertThat(hotel.status()).as(hotel.failureReason()).isEqualTo(BootstrappedPlugin.Status.READY);
            assertThat(squatterTables()).isZero();
        } finally {
            for (BootstrappedPlugin plugin : plugins) {
                if (plugin.classLoader() instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Exception ignored) {
                        // test cleanup only
                    }
                }
            }
        }
    }
}
