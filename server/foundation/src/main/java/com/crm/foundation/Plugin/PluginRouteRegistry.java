package com.crm.foundation.Plugin;

import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serves plugin controllers (design 6.3): a handler mapping of its own, next to
 * Foundation's, holding only what {@link #register} put there. Each plugin's
 * {@code @RestController} handler methods are mapped with their paths prefixed
 * by {@code /api/v1/plugins/{route}} and invoked on the plugin's beans (method
 * security and transaction proxies included); {@link #unregister} takes them
 * out again, so a stopped plugin's routes answer 404.
 *
 * <p>Default-deny: every handler method must carry {@code @PreAuthorize} (on
 * the method or its class), or the plugin's routes are refused as a whole —
 * an unguarded plugin endpoint would be open to every authenticated user.
 *
 * <p>The one exception is {@link PublicEndpoint}: a handler method may be public, but only under
 * {@code /public/}, and everything under {@code /public/} must be public (WebSecurityConfig
 * permits exactly {@code /api/v1/plugins/*}{@code /public/**} without a token).
 *
 * <p>It never detects handlers by itself ({@link #isHandler} is {@code false}),
 * so Foundation's controllers stay with Foundation's mapping.
 */
@Component
public class PluginRouteRegistry extends RequestMappingHandlerMapping {

    static final String PREFIX = "/api/v1/plugins/";

    /** Plugin-relative prefix of the login-free endpoints ({@link PublicEndpoint}). */
    static final String PUBLIC_PREFIX = "/public/";

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
     * @throws InvalidPluginPackageException if {@code route} is already registered, or a handler
     *         method has no {@code @PreAuthorize} on itself or its class (nothing is registered then)
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
                methods.forEach((method, mapping) -> requireMethodSecurity(method, userType, mapping));
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

    /**
     * Every handler is either guarded ({@code @PreAuthorize}, outside {@code /public/}) or public
     * ({@link PublicEndpoint}, only under {@code /public/}, never with {@code @PreAuthorize}).
     */
    private static void requireMethodSecurity(Method method, Class<?> userType, RequestMappingInfo mapping) {
        String where = userType.getName() + "." + method.getName();
        boolean guarded = MergedAnnotations.from(method, SearchStrategy.TYPE_HIERARCHY).isPresent(PreAuthorize.class)
            || MergedAnnotations.from(userType, SearchStrategy.TYPE_HIERARCHY).isPresent(PreAuthorize.class);
        boolean publicEndpoint = MergedAnnotations.from(method, SearchStrategy.TYPE_HIERARCHY)
            .isPresent(PublicEndpoint.class);
        Set<String> paths = mapping.getPatternValues();
        if (publicEndpoint) {
            if (paths.isEmpty() || !paths.stream().allMatch(path -> path.startsWith(PUBLIC_PREFIX))) {
                throw new InvalidPluginPackageException("handler method " + where + " is @PublicEndpoint but is mapped "
                    + "outside " + PUBLIC_PREFIX + "; public endpoints must live under " + PUBLIC_PREFIX);
            }
            if (guarded) {
                throw new InvalidPluginPackageException("handler method " + where
                    + " is @PublicEndpoint and also carries @PreAuthorize; a public endpoint has no login");
            }
            return;
        }
        if (paths.stream().anyMatch(path -> path.equals(PUBLIC_PREFIX.substring(0, PUBLIC_PREFIX.length() - 1))
                || path.startsWith(PUBLIC_PREFIX))) {
            throw new InvalidPluginPackageException("handler method " + where + " is mapped under " + PUBLIC_PREFIX
                + " but has no @PublicEndpoint; paths under " + PUBLIC_PREFIX + " are reserved for public endpoints");
        }
        if (!guarded) {
            throw new InvalidPluginPackageException("handler method " + where
                + " has no @PreAuthorize (on the method or its class); plugin endpoints are deny-by-default");
        }
    }

    /** Removes every mapping registered under {@code route}; a no-op for an unknown route. */
    public synchronized void unregister(String route) {
        List<RequestMappingInfo> registered = routes.remove(route);
        if (registered != null) {
            registered.forEach(this::unregisterMapping);
        }
    }
}
