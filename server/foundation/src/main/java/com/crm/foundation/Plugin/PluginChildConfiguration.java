package com.crm.foundation.Plugin;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Infrastructure of every plugin's Spring child context
 * ({@link PluginContextFactory}): {@code @PreAuthorize} method security and
 * {@code @Transactional} proxies for the plugin's own beans. Bean post-processors
 * don't cross context boundaries, so the parent's {@code @EnableMethodSecurity}
 * and transaction proxying never reach plugin beans; transactions still run on
 * the parent's {@code transactionManager}, found through the parent context.
 *
 * <p>{@code @EnableScheduling} makes a plugin bean's {@code @Scheduled} methods run on
 * Foundation's one shared {@code TaskScheduler} (resolved through the parent context, so
 * no second scheduler is created); the child's tasks are cancelled when it closes.
 *
 * <p>Class-based proxies, as in Spring Boot's parent context, so plugin code may
 * inject concrete service classes.
 *
 * <p>{@link InPluginContext}: Foundation's own component scan finds this class
 * too, but it only applies inside a plugin child context.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(PluginChildConfiguration.InPluginContext.class)
@EnableMethodSecurity(prePostEnabled = true, proxyTargetClass = true)
@EnableTransactionManagement(proxyTargetClass = true)
@EnableScheduling
public class PluginChildConfiguration {

    /** Matches only in a context that {@link PluginContextFactory} seeded with the plugin manifest. */
    static final class InPluginContext implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return context.getBeanFactory() != null
                && context.getBeanFactory().containsLocalBean(PluginContextFactory.MANIFEST_BEAN);
        }
    }
}
