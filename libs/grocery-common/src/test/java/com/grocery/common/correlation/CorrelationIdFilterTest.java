package com.grocery.common.correlation;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void reusesValidIncomingCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "abc-12345-xyz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInMdc.set(MDC.get(CorrelationId.MDC_KEY));
            }
        });

        assertThat(seenInMdc.get()).isEqualTo("abc-12345-xyz");
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("abc-12345-xyz");
        assertThat(MDC.get(CorrelationId.MDC_KEY)).as("MDC cleaned up after the request").isNull();
    }

    @Test
    void generatesNewIdWhenHeaderMissingOrUnsafe() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "bad value\nwith newline <script>");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String generated = response.getHeader(CorrelationId.HEADER);
        assertThat(generated).isNotNull().isNotEqualTo("bad value\nwith newline <script>");
        assertThat(CorrelationId.sanitize(generated)).isEqualTo(generated);
    }

    @Test
    void sanitizeRejectsTooShortOrTooLongValues() {
        assertThat(CorrelationId.sanitize("short")).isNull();
        assertThat(CorrelationId.sanitize("x".repeat(65))).isNull();
        assertThat(CorrelationId.sanitize("  valid-id-123  ")).isEqualTo("valid-id-123");
    }
}
