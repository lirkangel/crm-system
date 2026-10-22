package com.crm.foundation.Plugin;

import jakarta.persistence.Entity;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.net.URLClassLoader;
import java.util.List;

/**
 * Finds a plugin's JPA {@code @Entity} classes by name — reading class files
 * through the plugin's classloader without loading them.
 */
@Component
public class PluginEntityScanner {

    /**
     * {@code @Entity} class names under {@code basePackage} (and its
     * sub-packages), sorted. Only classes in the plugin's own jars count: a
     * package shared with the application (e.g. a {@code com.crm} prefix)
     * must not pull Foundation's entities in.
     */
    public List<String> scan(ClassLoader classLoader, String basePackage) {
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.setResourceLoader(new DefaultResourceLoader(classLoader));
        provider.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        return provider.findCandidateComponents(basePackage).stream()
            .map(BeanDefinition::getBeanClassName)
            .filter(className -> definedInPluginJars(classLoader, className))
            .sorted()
            .toList();
    }

    /** Whether {@code className} comes from the plugin's own jars (not from the application through the parent). */
    static boolean definedInPluginJars(ClassLoader classLoader, String className) {
        return !(classLoader instanceof URLClassLoader pluginJars)
            || pluginJars.findResource(className.replace('.', '/') + ".class") != null;
    }
}
