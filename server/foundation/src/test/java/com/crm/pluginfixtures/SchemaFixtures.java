package com.crm.pluginfixtures;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.util.Set;
import java.util.UUID;

/**
 * Plugin-like entities for {@code PluginEntityValidatorTest}, all meant for a
 * plugin whose schema is {@code plugin_x}. They sit outside
 * {@code com.crm.foundation} on purpose: Boot's entity scan covers that
 * package, and these must not join the real persistence unit of any
 * {@code @SpringBootTest}.
 */
public final class SchemaFixtures {

    private SchemaFixtures() {
    }

    @Entity
    @Table(schema = "plugin_x", name = "own")
    public static class Own {
        @Id
        private UUID id;
        @ElementCollection
        @CollectionTable(schema = "plugin_x", name = "own_tags")
        private Set<String> tags;
    }

    @Entity
    @Table(schema = "public", name = "users")
    public static class Escape {
        @Id
        private UUID id;
    }

    @Entity
    @Table(name = "no_schema")
    public static class NoSchema {
        @Id
        private UUID id;
    }

    /** Named like Foundation's {@code User} entity. */
    @Entity(name = "User")
    @Table(schema = "plugin_x", name = "named_user")
    public static class NamedUser {
        @Id
        private UUID id;
    }

    /** {@code @ElementCollection} without {@code @CollectionTable}: Hibernate invents the table. */
    @Entity
    @Table(schema = "plugin_x", name = "implicit_collection")
    public static class ImplicitCollection {
        @Id
        private UUID id;
        @ElementCollection
        private Set<UUID> roles;
    }

    /** {@code @ManyToMany} without {@code @JoinTable}: Hibernate invents the join table. */
    @Entity
    @Table(schema = "plugin_x", name = "implicit_join")
    public static class ImplicitJoin {
        @Id
        private UUID id;
        @ManyToMany
        private Set<Own> owns;
    }
}
