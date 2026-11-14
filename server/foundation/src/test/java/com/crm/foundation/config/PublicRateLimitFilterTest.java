package com.crm.foundation.config;

import com.crm.foundation.Config.PublicRateLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();

    private final MutableClock clock = new MutableClock();

    private PublicRateLimitFilter filter(int limit, String secret) {
        return new PublicRateLimitFilter(limit, secret, MAPPER, clock);
    }

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
        PublicRateLimitFilter filter = filter(2, "proxy-secret");

        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "1.1.1.1", null)).getStatus()).isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "2.2.2.2", null)).getStatus()).isEqualTo(200);
        MockHttpServletResponse third = call(filter, request(PUBLIC_PATH, "10.0.0.5", "3.3.3.3", null));

        assertThat(third.getStatus()).isEqualTo(429);
        // a wrong secret is no better than none
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "4.4.4.4", "wrong")).getStatus()).isEqualTo(429);
    }

    @Test
    void empty_configured_secret_trusts_no_proxy() throws Exception {
        PublicRateLimitFilter filter = filter(1, "");

        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "1.1.1.1", "")).getStatus()).isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "2.2.2.2", "")).getStatus()).isEqualTo(429);
    }

    @Test
    void client_ip_used_with_valid_secret() throws Exception {
        PublicRateLimitFilter filter = filter(1, "proxy-secret");

        // same remote address (the proxy), different guests: each has a bucket of its own
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "1.1.1.1", "proxy-secret")).getStatus()).isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "2.2.2.2", "proxy-secret")).getStatus()).isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "1.1.1.1", "proxy-secret")).getStatus()).isEqualTo(429);
    }

    @Test
    void non_public_paths_not_limited() throws Exception {
        PublicRateLimitFilter filter = filter(1, "proxy-secret");

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
        PublicRateLimitFilter filter = filter(1, "");
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
        PublicRateLimitFilter filter = filter(1, "");

        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", null, null)).getStatus()).isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", null, null)).getStatus()).isEqualTo(429);
        clock.advance(Duration.ofSeconds(61));

        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", null, null)).getStatus()).isEqualTo(200);
    }

    @Test
    void map_stays_bounded_and_keeps_serving() throws Exception {
        PublicRateLimitFilter filter = filter(1, "");

        for (int i = 0; i < 10_050; i++) {
            assertThat(call(filter, request(PUBLIC_PATH, "10.1." + (i / 256) + "." + (i % 256), null, null))
                .getStatus()).isEqualTo(200);
        }
    }

    @Test
    void encoded_public_segment_is_answered_400_with_the_public_headers() throws Exception {
        PublicRateLimitFilter filter = filter(1, "");

        for (String path : new String[] {"/api/v1/plugins/hotel/%70ublic/checkin/stay",
            "/api/v1/plugins/h%6Ftel/public/checkin/stay", "/api/v1/plugins/%68otel/public/x"}) {
            MockHttpServletResponse response = call(filter, request(path, "10.0.0.5", null, null));
            assertThat(response.getStatus()).as(path).isEqualTo(400);
            assertThat(response.getContentType()).isEqualTo("application/problem+json");
            assertThat(response.getContentAsString()).contains("\"code\":\"BAD_REQUEST\"");
        }
        MockHttpServletResponse decodedPublic = call(filter,
            request("/api/v1/plugins/hotel/%70ublic/x", "10.0.0.5", null, null));
        assertThat(decodedPublic.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(decodedPublic.getHeader("X-Robots-Tag")).isEqualTo("noindex");
        // encoding after the public segment is fine (an encoded value in a later segment)
        assertThat(call(filter, request("/api/v1/plugins/hotel/public/a%20b", "10.0.0.6", null, null)).getStatus())
            .isEqualTo(200);
    }

    @Test
    void ipv6_clients_share_a_bucket_per_slash_64() throws Exception {
        PublicRateLimitFilter filter = filter(1, "proxy-secret");

        assertThat(call(filter, request(PUBLIC_PATH, "2001:db8:1:2:aaaa::1", null, null)).getStatus()).isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "2001:db8:1:2:bbbb::2", null, null)).getStatus()).isEqualTo(429);
        assertThat(call(filter, request(PUBLIC_PATH, "2001:db8:1:3::1", null, null)).getStatus()).isEqualTo(200);
        // the same for a trusted proxy's X-Client-IP
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "2001:db8:9:9::1", "proxy-secret")).getStatus())
            .isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", "2001:db8:9:9:ffff::7", "proxy-secret")).getStatus())
            .isEqualTo(429);
        // IPv4 clients stay per full address
        assertThat(call(filter, request(PUBLIC_PATH, "192.0.2.1", null, null)).getStatus()).isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "192.0.2.2", null, null)).getStatus()).isEqualTo(200);
    }

    @Test
    void trusted_proxy_without_client_ip_falls_back_to_its_own_address() throws Exception {
        PublicRateLimitFilter filter = filter(1, "proxy-secret");

        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", null, "proxy-secret")).getStatus()).isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", " ", "proxy-secret")).getStatus()).isEqualTo(429);
    }

    @Test
    void limit_below_one_fails_startup_with_a_clear_message() {
        assertThatThrownBy(() -> filter(0, ""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("crm.public.rate-limit-per-minute must be at least 1");
    }

    @Test
    void client_ip_hostname_like_values_never_resolve() throws Exception {
        PublicRateLimitFilter filter = filter(1, "proxy-secret");

        for (String value : new String[] {"zzz:1", "host:1", "example.com", "a:b", "[nope]:1"}) {
            long start = System.nanoTime();
            assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", value, "proxy-secret")).getStatus())
                .as(value).isEqualTo(200);
            // opaque key: the same string again is the same bucket
            assertThat(call(filter, request(PUBLIC_PATH, "10.0.0.5", value, "proxy-secret")).getStatus())
                .as(value).isEqualTo(429);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as(value).isLessThan(Duration.ofSeconds(1));
        }
        assertThat(PublicRateLimitFilter.normalize("zzz:1")).isEqualTo("zzz:1");
        assertThat(PublicRateLimitFilter.normalize("example.com")).isEqualTo("example.com");
    }

    @Test
    void ipv4_mapped_ipv6_shares_ipv4_bucket() throws Exception {
        PublicRateLimitFilter filter = filter(1, "");

        assertThat(call(filter, request(PUBLIC_PATH, "1.2.3.4", null, null)).getStatus()).isEqualTo(200);
        assertThat(call(filter, request(PUBLIC_PATH, "::ffff:1.2.3.4", null, null)).getStatus()).isEqualTo(429);
        assertThat(PublicRateLimitFilter.normalize("[2001:db8::1]")).isEqualTo(PublicRateLimitFilter.normalize("2001:db8:0:0:1:2:3:4"));
    }

    @Test
    void proxy_fallback_warns_once_a_minute() throws Exception {
        int[] warnings = {0};
        PublicRateLimitFilter filter = new PublicRateLimitFilter(100, "proxy-secret", MAPPER, clock) {
            @Override
            protected void logProxyWarning() {
                warnings[0]++;
            }
        };

        call(filter, request(PUBLIC_PATH, "10.0.0.5", null, "proxy-secret"));
        call(filter, request(PUBLIC_PATH, "10.0.0.5", " ", "proxy-secret"));
        assertThat(warnings[0]).isEqualTo(1);
        clock.advance(Duration.ofSeconds(61));
        call(filter, request(PUBLIC_PATH, "10.0.0.5", null, "proxy-secret"));
        assertThat(warnings[0]).isEqualTo(2);
    }
}
