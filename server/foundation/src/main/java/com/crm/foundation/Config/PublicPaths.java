package com.crm.foundation.Config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.util.UrlPathHelper;

/**
 * The single definition of "public plugin path" ({@code /api/v1/plugins/*}{@code /public/**}), shared by
 * {@link WebSecurityConfig} (permitAll) and {@link PublicRateLimitFilter} (limit, headers), so they agree by
 * construction. It matches the decoded path within the application (percent-escapes decoded,
 * {@code ;params} stripped), the same view Spring MVC maps handlers on.
 */
public final class PublicPaths {

    public static final String PLUGINS_PREFIX = "/api/v1/plugins/";
    public static final String PATTERN = PLUGINS_PREFIX + "*/public/**";

    /** Matches exactly the requests {@link #isPublic} accepts. */
    public static final RequestMatcher MATCHER = PublicPaths::isPublic;

    private static final AntPathMatcher ANT = new AntPathMatcher();

    private PublicPaths() {
    }

    /** The decoded path within the application. */
    public static String decodedPath(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }

    public static boolean isPublic(HttpServletRequest request) {
        return ANT.match(PATTERN, decodedPath(request));
    }
}
