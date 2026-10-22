package com.crm.foundation.Plugin;

import com.crm.foundation.Service.AuditService;
import com.crm.foundation.Service.ChangeEventService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.beans.Introspector;

/**
 * Late plugin phase (design 6.1): builds a READY plugin's Spring child
 * context. The context loads classes through the plugin's own classloader —
 * the very one Hibernate already holds, so the plugin's entity classes are the
 * ones Foundation's {@code EntityManagerFactory} manages — and sees every
 * Foundation bean through its parent.
 *
 * <p>It holds {@link PluginChildConfiguration} (method security, transactions),
 * the plugin-bound {@link ChangeRecorder} and {@link AuditRecorder}, the
 * components found under the entry class's package, and one Spring Data JPA
 * repository per {@link Repository} interface found there (on Foundation's
 * {@code entityManagerFactory} and {@code transactionManager}).
 */
@Component
public class PluginContextFactory {

    /** Bean holding the plugin's {@link PluginManifest}; marks a context as a plugin child context. */
    static final String MANIFEST_BEAN = "pluginManifest";
    static final String CHANGE_RECORDER_BEAN = "changeRecorder";
    static final String AUDIT_RECORDER_BEAN = "auditRecorder";
    private static final String ENTITY_MANAGER_FACTORY_BEAN = "entityManagerFactory";
    private static final String TRANSACTION_MANAGER_BEAN = "transactionManager";

    private final ChangeEventService changeEvents;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public PluginContextFactory(ChangeEventService changeEvents, AuditService audit, CurrentUser currentUser) {
        this.changeEvents = changeEvents;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    /** Builds and refreshes the plugin's child context; closes it again if the refresh fails. */
    public ConfigurableApplicationContext start(BootstrappedPlugin plugin, ApplicationContext parent) {
        PluginManifest manifest = plugin.manifest();
        ClassLoader classLoader = plugin.classLoader();
        String basePackage = basePackage(manifest);

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        try {
            context.setId("plugin:" + manifest.id());
            context.setDisplayName("plugin " + manifest.id());
            context.setParent(parent);
            context.setClassLoader(classLoader);
            context.getBeanFactory().registerSingleton(MANIFEST_BEAN, manifest);
            context.getBeanFactory().registerSingleton(
                CHANGE_RECORDER_BEAN, new PluginChangeRecorder(manifest.id(), changeEvents));
            context.getBeanFactory().registerSingleton(
                AUDIT_RECORDER_BEAN, new PluginAuditRecorder(manifest.id(), audit, currentUser));
            context.register(PluginChildConfiguration.class);
            componentScanner(context, classLoader).scan(basePackage);
            registerRepositories(context, parent, classLoader, basePackage);
            context.refresh();
            return context;
        } catch (RuntimeException | LinkageError e) {
            context.close();
            throw e;
        }
    }

    private static void registerRepositories(
            AnnotationConfigApplicationContext context, ApplicationContext parent,
            ClassLoader classLoader, String basePackage) {
        EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(
            parent.getBean(ENTITY_MANAGER_FACTORY_BEAN, EntityManagerFactory.class));
        for (BeanDefinition candidate : repositoryScanner(classLoader).findCandidateComponents(basePackage)) {
            Class<?> repositoryInterface = ClassUtils.resolveClassName(candidate.getBeanClassName(), classLoader);
            RootBeanDefinition repository = new RootBeanDefinition(JpaRepositoryFactoryBean.class);
            repository.getConstructorArgumentValues().addIndexedArgumentValue(0, repositoryInterface);
            repository.getPropertyValues()
                .add("entityManager", entityManager)
                .add("transactionManager", TRANSACTION_MANAGER_BEAN);
            // Lets autowiring match the repository type without instantiating the factory bean first
            repository.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, repositoryInterface);
            context.registerBeanDefinition(
                Introspector.decapitalize(repositoryInterface.getSimpleName()), repository);
        }
    }

    /** Default component scan, limited to the plugin's own classes. */
    private static ClassPathBeanDefinitionScanner componentScanner(
            AnnotationConfigApplicationContext context, ClassLoader classLoader) {
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(context);
        scanner.addExcludeFilter(notInPluginJars(classLoader));
        return scanner;
    }

    /** Finds concrete repository interfaces ({@link Repository} subtypes without {@link NoRepositoryBean}). */
    private static ClassPathScanningCandidateComponentProvider repositoryScanner(ClassLoader classLoader) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface()
                    && definition.getMetadata().isIndependent()
                    && !definition.getMetadata().hasAnnotation(NoRepositoryBean.class.getName());
            }
        };
        scanner.setResourceLoader(new DefaultResourceLoader(classLoader));
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        scanner.addExcludeFilter(notInPluginJars(classLoader));
        return scanner;
    }

    /** A package shared with the application (e.g. a {@code com.crm} prefix) must not pull Foundation classes in. */
    private static TypeFilter notInPluginJars(ClassLoader classLoader) {
        return (reader, readerFactory) ->
            !PluginEntityScanner.definedInPluginJars(classLoader, reader.getClassMetadata().getClassName());
    }

    private static String basePackage(PluginManifest manifest) {
        int lastDot = manifest.entry().lastIndexOf('.');
        return lastDot < 0 ? "" : manifest.entry().substring(0, lastDot);
    }
}
