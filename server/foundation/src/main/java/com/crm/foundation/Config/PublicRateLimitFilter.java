package com.crm.foundation.Config;

import com.crm.foundation.Controller.Problems;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Guards the login-free plugin endpoints {@code /api/v1/plugins/{route}/public/**}
 * ({@code @PublicEndpoint}). On those paths only:
 * <ul>
 *   <li>every response is sent {@code Cache-Control: no-store} and {@code X-Robots-Tag: noindex}; the
 *       headers are set before the chain runs, so problems rendered later keep them;</li>
 *   <li>each client may make {@code crm.public.rate-limit-per-minute} requests per minute (fixed
 *       one-minute window per client); the next one is answered {@code 429} problem {@code RATE_LIMITED}
 *       with {@code Retry-After}.</li>
 * </ul>
 * Paths are matched decoded ({@link PublicPaths}), like security and MVC do. Anything under
 * {@code /api/v1/plugins/} whose raw URI percent-encodes the route or {@code public} segment is answered
 * {@code 400}: legitimate clients never encode them, and the encoded form is only ever an attempt to dodge a
 * raw-path rule.
 *
 * <p>The client is the remote address (IPv6 addresses count by their /64 prefix), unless the request carries {@code X-Guest-Proxy-Secret} equal to
 * a non-empty {@code crm.public.proxy-secret}: then the trusted proxy's {@code X-Client-IP} names it.
 * The secret is compared in constant time; an empty configured secret trusts no proxy.
 *
 * <p>Counters live in a bounded map: windows idle for 10 minutes are dropped, and at 10,000 clients
 * an arbitrary one makes room (the limit then degrades for that client rather than refusing guests).
 */
@Component
public class PublicRateLimitFilter extends OncePerRequestFilter {

    public static final String PROXY_SECRET_HEADER = "X-Guest-Proxy-Secret";
    public static final String CLIENT_IP_HEADER = "X-Client-IP";

    static final long WINDOW_MILLIS = 60_000;
    static final long IDLE_EVICTION_MILLIS = 10 * 60_000;
    static final int MAX_KEYS = 10_000;
    private static final int MAX_CLIENT_IP_LENGTH = 64;
    /** Raw path segments that must not be percent-encoded: api, v1, plugins, route, public. */
    private static final int UNENCODED_SEGMENTS = 5;

    private static final class Window {
        long start;
        long lastSeen;
        int count;
    }

    private final int limitPerMinute;
    private final byte[] proxySecret;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final AtomicLong lastProxyWarning = new AtomicLong(Long.MIN_VALUE);
    private long lastSweep;

    @Autowired
    public PublicRateLimitFilter(
            @Value("${crm.public.rate-limit-per-minute:60}") int limitPerMinute,
            @Value("${crm.public.proxy-secret:}") String proxySecret,
            ObjectMapper objectMapper) {
        this(limitPerMinute, proxySecret, objectMapper, Clock.systemUTC());
    }

    public PublicRateLimitFilter(int limitPerMinute, String proxySecret, ObjectMapper objectMapper, Clock clock) {
        if (limitPerMinute < 1) {
            throw new IllegalStateException(
                "crm.public.rate-limit-per-minute must be at least 1 (was " + limitPerMinute + ")");
        }
        this.limitPerMinute = limitPerMinute;
        this.proxySecret = proxySecret == null ? new byte[0] : proxySecret.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !PublicPaths.decodedPath(request).startsWith(PublicPaths.PLUGINS_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain) throws ServletException, IOException {
        boolean isPublic = PublicPaths.isPublic(request);
        if (isPublic) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Robots-Tag", "noindex");
        }
        if (hasEncodedRouteSegment(request)) {
            writeProblem(response, Problems.of(HttpStatus.BAD_REQUEST, Problems.TYPE_BAD_REQUEST, "BAD_REQUEST",
                "Invalid request path"));
            return;
        }
        if (isPublic) {
            long retryAfterSeconds = tryAcquire(clientKey(request));
            if (retryAfterSeconds > 0) {
                response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
                writeProblem(response, Problems.of(HttpStatus.TOO_MANY_REQUESTS, Problems.TYPE_RATE_LIMITED,
                    "RATE_LIMITED", "Too many requests; try again shortly"));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void writeProblem(HttpServletResponse response, org.springframework.http.ProblemDetail problem)
            throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType("application/problem+json");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    /** True when the raw URI percent-encodes any of the first segments ({@code api/v1/plugins/<route>/public}). */
    private static boolean hasEncodedRouteSegment(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        String[] segments = uri.split("/", UNENCODED_SEGMENTS + 2);
        for (int i = 1; i <= UNENCODED_SEGMENTS && i < segments.length; i++) {
            if (segments[i].indexOf('%') >= 0) {
                return true;
            }
        }
        return false;
    }

    private String clientKey(HttpServletRequest request) {
        String presented = request.getHeader(PROXY_SECRET_HEADER);
        if (proxySecret.length > 0 && presented != null
                && MessageDigest.isEqual(proxySecret, presented.getBytes(StandardCharsets.UTF_8))) {
            String forwarded = request.getHeader(CLIENT_IP_HEADER);
            if (forwarded != null && !forwarded.isBlank() && forwarded.length() <= MAX_CLIENT_IP_LENGTH) {
                return normalize(forwarded.trim());
            }
            warnProxyWithoutClientIp();
        }
        return normalize(request.getRemoteAddr());
    }

    /** IPv4 (and anything unparseable) by its text; IPv6 by its /64 prefix, which one subscriber controls. */
    private static String normalize(String address) {
        if (address.indexOf(':') >= 0) {
            try {
                // contains ':' so this is a literal parse, never a DNS lookup
                byte[] bytes = InetAddress.getByName(address).getAddress();
                if (bytes.length == 16) {
                    StringBuilder prefix = new StringBuilder("v6:");
                    for (int i = 0; i < 8; i++) {
                        prefix.append(String.format("%02x", bytes[i]));
                    }
                    return prefix.toString();
                }
                return InetAddress.getByAddress(bytes).getHostAddress();
            } catch (UnknownHostException e) {
                return address;
            }
        }
        return address;
    }

    /** A trusted proxy that sends no usable X-Client-IP is misconfigured; say so at most once a minute. */
    private void warnProxyWithoutClientIp() {
        long now = clock.millis();
        long last = lastProxyWarning.get();
        if ((last == Long.MIN_VALUE || now - last >= WINDOW_MILLIS) && lastProxyWarning.compareAndSet(last, now)) {
            logger.warn("Trusted guest proxy sent no usable X-Client-IP; rate limiting by its own address");
        }
    }

    /** Counts one request for {@code key}; returns 0 when allowed, else the seconds until the window ends. */
    private long tryAcquire(String key) {
        long now = clock.millis();
        sweepIfDue(now);
        Window window = windows.get(key);
        if (window == null) {
            if (windows.size() >= MAX_KEYS) {
                Iterator<String> it = windows.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            window = windows.computeIfAbsent(key, k -> new Window());
        }
        synchronized (window) {
            window.lastSeen = now;
            if (window.count == 0 || now - window.start >= WINDOW_MILLIS) {
                window.start = now;
                window.count = 0;
            }
            if (window.count < limitPerMinute) {
                window.count++;
                return 0;
            }
            return Math.max(1, (window.start + WINDOW_MILLIS - now + 999) / 1000);
        }
    }

    private synchronized void sweepIfDue(long now) {
        if (now - lastSweep < WINDOW_MILLIS) {
            return;
        }
        lastSweep = now;
        windows.entrySet().removeIf(entry -> now - entry.getValue().lastSeen >= IDLE_EVICTION_MILLIS);
    }
}
