package com.crm.foundation.Plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.util.ServletRequestPathUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginRouteRegistryTest {

    @RestController
    static class PluginController {
        @GetMapping("/ping")
        @PreAuthorize("hasAuthority('echo.ping')")
        public String ping() {
            return "pong";
        }
    }

    /** Guarded at class level: every handler method inherits the check. */
    @RestController
    @PreAuthorize("hasAuthority('echo.ping')")
    static class ClassGuardedController {
        @GetMapping("/guarded")
        public String guarded() {
            return "guarded";
        }
    }

    /** One guarded and one unguarded handler method. */
    @RestController
    static class PartlyOpenController {
        @GetMapping("/closed")
        @PreAuthorize("hasAuthority('echo.ping')")
        public String closed() {
            return "closed";
        }

        @GetMapping("/open")
        public String open() {
            return "open";
        }
    }

    @RestController
    static class FoundationController {
        @GetMapping("/api/v1/foundation-only")
        public String foundation() {
            return "foundation";
        }
    }

    private final GenericApplicationContext parent = new GenericApplicationContext();
    private final AnnotationConfigApplicationContext pluginContext = new AnnotationConfigApplicationContext();
    private final PluginRouteRegistry registry = new PluginRouteRegistry();

    @BeforeEach
    void setUp() {
        parent.registerBean(FoundationController.class);
        parent.refresh();
        pluginContext.setParent(parent);
        pluginContext.register(PluginController.class);
        pluginContext.refresh();
        registry.setApplicationContext(parent);
        registry.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        pluginContext.close();
        parent.close();
    }

    private Object handlerFor(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        ServletRequestPathUtils.parseAndCache(request);
        HandlerExecutionChain chain = registry.getHandler(request);
        return chain == null ? null : chain.getHandler();
    }

    @Test
    void never_detects_handlers_on_its_own() throws Exception {
        assertThat(registry.getHandlerMethods()).isEmpty();
        assertThat(handlerFor("/api/v1/foundation-only")).isNull();
    }

    @Test
    void maps_local_rest_controllers_under_the_plugin_prefix() throws Exception {
        registry.register("echo", pluginContext);

        assertThat(handlerFor("/api/v1/plugins/echo/ping")).isInstanceOfSatisfying(HandlerMethod.class, handler -> {
            assertThat(handler.getBean()).isSameAs(pluginContext.getBean(PluginController.class));
            assertThat(handler.getMethod().getName()).isEqualTo("ping");
        });
        assertThat(handlerFor("/ping")).isNull();
        // Ancestors' controllers are not the plugin's
        assertThat(registry.getHandlerMethods()).hasSize(1);
    }

    @Test
    void rejects_an_already_registered_route() {
        registry.register("echo", pluginContext);

        assertThatThrownBy(() -> registry.register("echo", pluginContext))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("echo");
        assertThat(registry.getHandlerMethods()).hasSize(1);
    }

    @Test
    void accepts_a_class_level_pre_authorize() throws Exception {
        AnnotationConfigApplicationContext guarded = childWith(ClassGuardedController.class);
        try {
            registry.register("guarded", guarded);

            assertThat(handlerFor("/api/v1/plugins/guarded/guarded")).isNotNull();
        } finally {
            guarded.close();
        }
    }

    @Test
    void refuses_a_handler_method_without_method_security() throws Exception {
        AnnotationConfigApplicationContext partlyOpen = childWith(PartlyOpenController.class);
        try {
            assertThatThrownBy(() -> registry.register("open", partlyOpen))
                .isInstanceOf(InvalidPluginPackageException.class)
                .hasMessageContaining("@PreAuthorize")
                .hasMessageContaining(PartlyOpenController.class.getName() + ".open");

            // all or nothing: the guarded method of the refused plugin is not mapped either
            assertThat(registry.getHandlerMethods()).isEmpty();
            assertThat(handlerFor("/api/v1/plugins/open/closed")).isNull();
            assertThat(handlerFor("/api/v1/plugins/open/open")).isNull();
            // and the route stays free
            registry.register("open", pluginContext);
            assertThat(handlerFor("/api/v1/plugins/open/ping")).isNotNull();
        } finally {
            partlyOpen.close();
        }
    }

    private AnnotationConfigApplicationContext childWith(Class<?> controller) {
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.setParent(parent);
        child.register(controller);
        child.refresh();
        return child;
    }

    @Test
    void unregister_removes_the_routes() throws Exception {
        registry.register("echo", pluginContext);

        registry.unregister("echo");

        assertThat(handlerFor("/api/v1/plugins/echo/ping")).isNull();
        assertThat(registry.getHandlerMethods()).isEmpty();
        registry.register("echo", pluginContext);
        assertThat(handlerFor("/api/v1/plugins/echo/ping")).isNotNull();
    }
}
