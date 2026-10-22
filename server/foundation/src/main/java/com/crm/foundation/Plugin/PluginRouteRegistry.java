package com.crm.foundation.Plugin;

import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves plugin controllers (design 6.3): a handler mapping of its own, next to
 * Foundation's, holding only what {@link #register} put there. Each plugin's
 * {@code @RestController} handler methods are mapped with their paths prefixed
 * by {@code /api/v1/plugins/{route}} and invoked on the plugin's beans (method
 * security and transaction proxies included); {@link #unregister} takes them
 * out again, so a stopped plugin's routes answer 404.
 *
 * <p>It never detects handlers by itself ({@link #isHandler} is {@code false}),
 * so Foundation's controllers stay with Foundation's mapping.
 */
@Component
public class PluginRouteRegistry extends RequestMappingHandlerMapping {

    static final String PREFIX = "/api/v1/plugins/";

    /** route → its registered mappings. */
    private final Map<String, List<RequestMappingInfo>> routes = new HashMap<>();

    public PluginRouteRegistry() {
        setOrder(0);
    }

    @Override
    protected boolean isHandler(Class<?> beanType) {
        return false;
    }

    /**
     * Maps every handler method of the {@code @RestController} beans defined in
     * {@code pluginContext} itself (not its ancestors) under {@code /api/v1/plugins/{route}}.
     *
     * @throws InvalidPluginPackageException if {@code route} is already registered
     */
    public synchronized void register(String route, ApplicationContext pluginContext) {
        if (routes.containsKey(route)) {
            throw new InvalidPluginPackageException("route '" + route + "' is already registered by another plugin");
        }
        RequestMappingInfo prefix = RequestMappingInfo.paths(PREFIX + route).options(getBuilderConfiguration()).build();
        List<RequestMappingInfo> registered = new ArrayList<>();
        try {
            for (String beanName : pluginContext.getBeanNamesForAnnotation(RestController.class)) {
                Object handler = pluginContext.getBean(beanName);
                Class<?> userType = AopProxyUtils.ultimateTargetClass(handler);
                Map<Method, RequestMappingInfo> methods = MethodIntrospector.selectMethods(userType,
                    (MethodIntrospector.MetadataLookup<RequestMappingInfo>) method -> getMappingForMethod(method, userType));
                for (Map.Entry<Method, RequestMappingInfo> entry : methods.entrySet()) {
                    RequestMappingInfo mapping = prefix.combine(entry.getValue());
                    registerMapping(mapping, handler, AopUtils.selectInvocableMethod(entry.getKey(), handler.getClass()));
                    registered.add(mapping);
                }
            }
        } catch (RuntimeException e) {
            registered.forEach(this::unregisterMapping);
            throw e;
        }
        routes.put(route, registered);
        logger.info("Plugin route " + PREFIX + route + " registered with " + registered.size() + " handler method(s)");
    }

    /** Removes every mapping registered under {@code route}; a no-op for an unknown route. */
    public synchronized void unregister(String route) {
        List<RequestMappingInfo> registered = routes.remove(route);
        if (registered != null) {
            registered.forEach(this::unregisterMapping);
        }
    }
}
