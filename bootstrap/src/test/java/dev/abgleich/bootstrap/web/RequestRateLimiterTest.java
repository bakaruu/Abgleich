package dev.abgleich.bootstrap.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.bootstrap.web.RequestRateLimiter.Decision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T10:00:00Z"));

    @Test
    void B44_upload_rate_is_limited() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new RequestRateLimiter(3, Duration.ofMinutes(1), clock, 100));
        AtomicInteger reachedTheApplication = new AtomicInteger();

        int[] statuses = new int[4];
        for (int i = 0; i < 4; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(upload("203.0.113.7", "/statements"), response,
                    (request, ignored) -> reachedTheApplication.incrementAndGet());
            statuses[i] = response.getStatus();
            if (i == 3) {
                assertThat(response.getHeader("Retry-After")).isEqualTo("60");
                assertThat(response.getContentAsString()).contains("Too many changes").doesNotContain("203.0.113.7");
            }
        }

        assertThat(statuses).containsExactly(200, 200, 200, 429);
        assertThat(reachedTheApplication).hasValue(3);
    }

    @Test
    void B44_reading_pages_is_free_and_other_clients_keep_their_own_budget() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new RequestRateLimiter(1, Duration.ofMinutes(1), clock, 100));
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(upload("203.0.113.7", "/api/v1/statements"), first, (request, response) -> { });
        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(upload("203.0.113.7", "/api/v1/statements"), refused, (request, response) -> { });

        MockHttpServletRequest page = new MockHttpServletRequest("GET", "/review");
        page.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse read = new MockHttpServletResponse();
        filter.doFilter(page, read, (request, response) -> { });
        MockHttpServletResponse otherClient = new MockHttpServletResponse();
        filter.doFilter(upload("198.51.100.20", "/api/v1/statements"), otherClient, (request, response) -> { });

        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getContentType()).startsWith("application/problem+json");
        assertThat(refused.getContentAsString()).contains("urn:abgleich:problem:rate-limited");
        assertThat(read.getStatus()).isEqualTo(200);
        assertThat(otherClient.getStatus()).isEqualTo(200);
    }

    @Test
    void the_budget_returns_when_the_window_ends() {
        RequestRateLimiter limiter = new RequestRateLimiter(2, Duration.ofMinutes(1), clock, 100);
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");
        clock.advance(Duration.ofSeconds(45));

        assertThat(limiter.tryAcquire("a")).isEqualTo(new Decision(false, 15));

        clock.advance(Duration.ofSeconds(15));
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    void B44_a_flood_of_addresses_cannot_grow_the_table_without_bound() {
        RequestRateLimiter limiter = new RequestRateLimiter(5, Duration.ofMinutes(1), clock, 2);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();

        assertThat(limiter.tryAcquire("c").allowed()).as("table full").isFalse();
        assertThat(limiter.tryAcquire("a").allowed()).as("known clients continue").isTrue();

        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("c").allowed()).as("expired windows make room").isTrue();
    }

    private static MockHttpServletRequest upload(String address, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(address);
        return request;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
