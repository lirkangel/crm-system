package com.crm.foundation.Plugin;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryBuilderCustomizer;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Adds READY plugins' {@code @Entity} classes to Foundation's persistence unit
 * and hands their classloaders to Hibernate ({@link AvailableSettings#CLASSLOADERS}),
 * so plugin entities share Foundation's {@code EntityManagerFactory} and
 * transaction manager (design 6.1).
 *
 * <p>Both hooks run while Spring Boot builds the {@code EntityManagerFactory};
 * {@link PluginBootstrap#report()} is consulted only then, never at bean
 * construction.
 */
@Component
public class PluginJpaContributor implements EntityManagerFactoryBuilderCustomizer, HibernatePropertiesCustomizer {

    private final PluginBootstrap bootstrap;

    public PluginJpaContributor(PluginBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public void customize(EntityManagerFactoryBuilder builder) {
        builder.setPersistenceUnitPostProcessors(persistenceUnit -> {
            for (BootstrappedPlugin plugin : bootstrap.report().ready()) {
                plugin.entityClassNames().forEach(persistenceUnit::addManagedClassName);
            }
        });
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        List<ClassLoader> classLoaders = bootstrap.report().ready().stream()
            .map(BootstrappedPlugin::classLoader)
            .toList();
        if (!classLoaders.isEmpty()) {
            hibernateProperties.put(AvailableSettings.CLASSLOADERS, classLoaders);
        }
    }
}
