package dev.abgleich.bootstrap.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final List<String> idsSeenByTheApplication = new ArrayList<>();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void every_request_gets_an_id_that_the_response_repeats() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest("GET", "/review"));

        assertThat(idsSeenByTheApplication).singleElement().asString().isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(idsSeenByTheApplication.getFirst());
    }

    @Test
    void a_caller_may_supply_its_own_id_to_tie_both_systems_together() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/invoices");
        request.addHeader(CorrelationIdFilter.HEADER, "erp-2026-11-20-0042");

        MockHttpServletResponse response = run(request);

        assertThat(idsSeenByTheApplication).containsExactly("erp-2026-11-20-0042");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("erp-2026-11-20-0042");
    }

    /** B41: the header is untrusted, so nothing from it reaches the logs unless it is plainly harmless. */
    @Test
    void a_header_that_could_forge_a_log_line_is_replaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/review");
        request.addHeader(CorrelationIdFilter.HEADER, "id\n{\"message\":\"admin logged in\"}");

        run(request);

        assertThat(idsSeenByTheApplication).singleElement().asString()
                .doesNotContain("admin").doesNotContain("\n").matches("[A-Za-z0-9]{16}");
    }

    @Test
    void two_requests_never_share_an_id_and_none_is_left_behind() throws Exception {
        run(new MockHttpServletRequest("GET", "/review"));
        run(new MockHttpServletRequest("GET", "/invoices"));

        assertThat(idsSeenByTheApplication).doesNotHaveDuplicates();
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response,
                (ignored, alsoIgnored) -> idsSeenByTheApplication.add(MDC.get(CorrelationIdFilter.MDC_KEY)));
        return response;
    }
}
