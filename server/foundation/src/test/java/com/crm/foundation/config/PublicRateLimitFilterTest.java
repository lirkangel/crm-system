package com.crm.foundation.config;

import com.crm.foundation.Config.PublicRateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRateLimitFilterTest {

    private static final String PUBLIC_PATH = "/api/v1/plugins/hotel/public/checkin/stay";

    /** A clock the test moves by hand, so no test sleeps. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-14T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();

    private static MockHttpServletRequest request(String path, String remote, String clientIp, String secret) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(remote);
        if (clientIp != null) {
            request.addHeader("X-Client-IP", clientIp);
        }
        if (secret != null) {
            request.addHeader("X-Guest-Proxy-Secret", secret);
        }
        return request;
    }

    private MockHttpServletResponse call(PublicRateLimitFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void forged_client_ip_without_secret_is_ignored() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter(2, "proxy-secret", clock);

        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "1.1.1.1", null)).getStatus()).isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "2.2.2.2", null)).getStatus()).isEqualTo(200);
        MockHttpServletResponse third = call(filter, request(PUBLIC_PATH, "10.0.0.5", "3.3.3.3", null));

        assertThat(third.getStatus()).isEqualTo(429);
        // a wrong secret is no better than none
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "4.4.4.4", "wrong")).getStatus()).isEqualTo(429);
    }

    @Test
    void empty_configured_secret_trusts_no_proxy() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter(1, "", clock);

        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "1.1.1.1", "")).getStatus()).isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "2.2.2.2", "")).getStatus()).isEqualTo(429);
    }

    @Test
    void client_ip_used_with_valid_secret() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter(1, "proxy-secret", clock);

        // same remote address (the proxy), different guests: each has a bucket of its own
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "1.1.1.1", "proxy-secret")).getStatus()).isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "2.2.2.2", "proxy-secret")).getStatus()).isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "1.1.1.1", "proxy-secret")).getStatus()).isEqualTo(429);
    }

    @Test
    void non_public_paths_not_limited() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter(1, "proxy-secret", clock);

        for (String path : new String[] {"/api/v1/plugins/hotel/reservations", "/api/v1/auth/login",
            "/api/v1/plugins/hotel/publicity", "/api/v1/plugins/public/x"}) {
            for (int i = 0; i < 3; i++) {
                MockHttpServletResponse response = call(filter, request(path, "10.0.0.5", null, null));
                assertThat(response.getStatus()).as(path).isEqualTo(200);
                assertThat(response.getHeader("Cache-Control")).as(path).isNull();
            }
        }
    }

    @Test
    void public_paths_carry_headers_and_429_is_a_problem() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter(1, "", clock);
        MockHttpServletResponse ok = call(filter, request(PUBLIC_PATH, "10.0.0.5", null, null));
        MockHttpServletResponse limited = call(filter, request(PUBLIC_PATH, "10.0.0.5", null, null));

        assertThat(ok.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(ok.getHeader("X-Robots-Tag")).isEqualTo("noindex");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(limited.getHeader("X-Robots-Tag")).isEqualTo("noindex");
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getContentType()).startsWith("application/problem+json");
        assertThat(limited.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"").contains("\"status\":429")
            .contains("\"trace_id\"");
    }

    @Test
    void window_resets_after_a_minute() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter(1, "", clock);

        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", null, null)).getStatus()).isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", null, null)).getStatus()).isEqualTo(429);
        clock.advance(Duration.ofSeconds(61));

        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", null, null)).getStatus()).isEqualTo(200);
    }

    @Test
    void map_stays_bounded_and_keeps_serving() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter(1, "", clock);

        for (int i = 0; i < 10_050; i++) {
            assertThat(call(filter, request(PUBLIC_PATH, "10.1." + (i / 256) + "." + (i % 256), null, null))
                .getStatus()).isEqualTo(200);
        }
    }
}
