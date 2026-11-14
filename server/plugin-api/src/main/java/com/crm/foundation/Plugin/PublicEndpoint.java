package com.crm.foundation.Plugin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a plugin handler method as reachable without login (public website use,
 * e.g. a guest's personal link). The foundation accepts it only under the plugin's
 * {@code /public/} path prefix: every mapped path of the method must start with
 * {@code /public/}, and every handler under {@code /public/} must carry this
 * annotation and no {@code @PreAuthorize}. Such requests have no Authentication;
 * they are rate limited per client, and their responses are sent
 * {@code Cache-Control: no-store} and {@code X-Robots-Tag: noindex}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicEndpoint {
}
