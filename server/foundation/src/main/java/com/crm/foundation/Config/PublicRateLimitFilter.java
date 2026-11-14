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
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    /** Raw path segments 1..5: api, v1, plugins, route, and the one that may be {@code public}. */
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

    /**
     * True when the raw URI percent-encodes the {@code api/v1/plugins/<route>} segments, or encodes a fifth
     * segment that decodes to {@code public} (which would only be an attempt to dodge a raw-path rule).
     * Other fifth segments may be encoded: guarded routes can take a path variable there.
     */
    private static boolean hasEncodedRouteSegment(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        String[] segments = uri.split("/", UNENCODED_SEGMENTS + 2);
        for (int i = 1; i < UNENCODED_SEGMENTS && i < segments.length; i++) {
            if (segments[i].indexOf('%') >= 0) {
                return true;
            }
        }
        if (segments.length > UNENCODED_SEGMENTS && segments[UNENCODED_SEGMENTS].indexOf('%') >= 0) {
            try {
                return "public".equals(UriUtils.decode(segments[UNENCODED_SEGMENTS], StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                return false;
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

    /**
     * IPv4 by its canonical dotted text, IPv6 by its /64 prefix (one subscriber controls a /64), an
     * IPv4-mapped IPv6 address as its IPv4. Only strict literals are parsed, by hand: the value is never
     * handed to the JDK's address resolver, so no value can cause a DNS lookup. Anything else
     * (hostnames, junk) is an opaque key, the string as given.
     */
    public static String normalize(String address) {
        String literal = address;
        if (literal.length() > 2 && literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        if (literal.indexOf(':') >= 0) {
            byte[] bytes = literal.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == ':' || c == '.')
                ? parseIpv6(literal) : null;
            if (bytes == null) {
                return address;
            }
            boolean mapped = (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
            for (int i = 0; i < 10 && mapped; i++) {
                mapped = bytes[i] == 0;
            }
            if (mapped) {
                return (bytes[12] & 0xff) + "." + (bytes[13] & 0xff) + "." + (bytes[14] & 0xff) + "." + (bytes[15] & 0xff);
            }
            StringBuilder prefix = new StringBuilder("v6:");
            for (int i = 0; i < 8; i++) {
                prefix.append(String.format("%02x", bytes[i]));
            }
            return prefix.toString();
        }
        byte[] v4 = parseIpv4(literal);
        return v4 == null ? address
            : (v4[0] & 0xff) + "." + (v4[1] & 0xff) + "." + (v4[2] & 0xff) + "." + (v4[3] & 0xff);
    }

    private static byte[] parseIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return null;
            }
            int value = Integer.parseInt(part);
            if (value > 255) {
                return null;
            }
            out[i] = (byte) value;
        }
        return out;
    }

    /** Strict IPv6 literal (RFC 4291 text forms, optional embedded IPv4 tail) to 16 bytes, or null. */
    private static byte[] parseIpv6(String s) {
        int gap = s.indexOf("::");
        if (gap != s.lastIndexOf("::")) {
            return null;
        }
        int[] head = ipv6Groups(gap < 0 ? s : s.substring(0, gap), gap < 0);
        int[] tail = gap < 0 ? new int[0] : ipv6Groups(s.substring(gap + 2), true);
        if (head == null || tail == null) {
            return null;
        }
        int total = head.length + tail.length;
        if (gap < 0 ? total != 8 : total > 7) {
            return null;
        }
        int[] groups = new int[8];
        System.arraycopy(head, 0, groups, 0, head.length);
        System.arraycopy(tail, 0, groups, 8 - tail.length, tail.length);
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[2 * i] = (byte) (groups[i] >> 8);
            out[2 * i + 1] = (byte) groups[i];
        }
        return out;
    }

    /** Colon-separated 16-bit groups; a dotted IPv4 may end the list when {@code last} is set. */
    private static int[] ipv6Groups(String part, boolean last) {
        if (part.isEmpty()) {
            return new int[0];
        }
        String[] tokens = part.split(":", -1);
        int[] groups = new int[tokens.length + 1];
        int n = 0;
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.indexOf('.') >= 0) {
                byte[] v4 = i == tokens.length - 1 && last ? parseIpv4(token) : null;
                if (v4 == null) {
                    return null;
                }
                groups[n++] = ((v4[0] & 0xff) << 8) | (v4[1] & 0xff);
                groups[n++] = ((v4[2] & 0xff) << 8) | (v4[3] & 0xff);
            } else {
                if (token.isEmpty() || token.length() > 4) {
                    return null;
                }
                groups[n++] = Integer.parseInt(token, 16);
            }
        }
        return java.util.Arrays.copyOf(groups, n);
    }

    /** A trusted proxy that sends no usable X-Client-IP is misconfigured; say so at most once a minute. */
    private void warnProxyWithoutClientIp() {
        long now = clock.millis();
        long last = lastProxyWarning.get();
        if ((last == Long.MIN_VALUE || now - last >= WINDOW_MILLIS) && lastProxyWarning.compareAndSet(last, now)) {
            logProxyWarning();
        }
    }

    /** Fixed message only: never the header value. */
    protected void logProxyWarning() {
        logger.warn("Trusted guest proxy sent no usable X-Client-IP; rate limiting by its own address");
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
