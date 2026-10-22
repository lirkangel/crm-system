package com.crm.foundation.Plugin;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Parent-last (child-first) classloader: plugin jars win over the application
 * classpath so plugins can ship their own dependency versions. JDK classes and
 * the shared foundation API ({@code com.crm.foundation.*}) always delegate to
 * the parent — otherwise plugin classes couldn't implement
 * {@link PluginActivator} castably.
 */
public class ParentLastClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    public ParentLastClassLoader(String pluginId, URL[] urls, ClassLoader parent) {
        super("plugin-" + pluginId, urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (mustComeFromParent(name)) {
                    loaded = super.loadClass(name, false);
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException notLocal) {
                        loaded = super.loadClass(name, false);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private static boolean mustComeFromParent(String name) {
        return name.startsWith("java.")
            || name.startsWith("javax.")
            || name.startsWith("jdk.")
            || name.startsWith("com.crm.foundation.");
    }
}
