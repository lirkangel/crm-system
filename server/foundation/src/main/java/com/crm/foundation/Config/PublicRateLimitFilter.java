package com.crm.foundation.Config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
 * The client is the remote address, unless the request carries {@code X-Guest-Proxy-Secret} equal to
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
    private static final Pattern PUBLIC_PATH = Pattern.compile("^/api/v1/plugins/[^/]+/public(/.*)?$");

    private static final class Window {
        long start;
        long lastSeen;
        int count;
    }

    private final int limitPerMinute;
    private final byte[] proxySecret;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private long lastSweep;

    @Autowired
    public PublicRateLimitFilter(
            @Value("${crm.public.rate-limit-per-minute:60}") int limitPerMinute,
            @Value("${crm.public.proxy-secret:}") String proxySecret) {
        this(limitPerMinute, proxySecret, Clock.systemUTC());
    }

    public PublicRateLimitFilter(int limitPerMinute, String proxySecret, Clock clock) {
        this.limitPerMinute = limitPerMinute;
        this.proxySecret = proxySecret == null ? new byte[0] : proxySecret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !PUBLIC_PATH.matcher(pathWithinApplication(request)).matches();
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Robots-Tag", "noindex");
        long retryAfterSeconds = tryAcquire(clientKey(request));
        if (retryAfterSeconds > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
            response.setContentType("application/problem+json");
            String problem = "{\"type\":\"urn:problem-type:rate-limited\",\"title\":\"Too Many Requests\","
                + "\"status\":429,\"detail\":\"Too many requests; try again shortly\","
                + "\"trace_id\":\"" + UUID.randomUUID() + "\",\"code\":\"RATE_LIMITED\"}";
            response.getOutputStream().write(problem.getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    private String clientKey(HttpServletRequest request) {
        String presented = request.getHeader(PROXY_SECRET_HEADER);
        if (proxySecret.length > 0 && presented != null
                && MessageDigest.isEqual(proxySecret, presented.getBytes(StandardCharsets.UTF_8))) {
            String forwarded = request.getHeader(CLIENT_IP_HEADER);
            if (forwarded != null && !forwarded.isBlank() && forwarded.length() <= MAX_CLIENT_IP_LENGTH) {
                return forwarded.trim();
            }
        }
        return request.getRemoteAddr();
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
