package org.example.agenthub.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BodySizeLimitFilterTest {

    final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    final BodySizeLimitFilter filter = new BodySizeLimitFilter(10, mapper);
    final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void declaredOversizedBodyIsRejectedBeforeReading() throws Exception {
        MockHttpServletRequest request = post(new byte[11]);
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(called).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(mapper.readTree(response.getContentAsString()).get("code").asText()).isEqualTo("payload_too_large");
    }

    @Test
    void bodyWithinLimitPasses() throws Exception {
        MockHttpServletRequest request = post(new byte[10]);
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> {
            req.getInputStream().readAllBytes();
            called.set(true);
        });

        assertThat(called).isTrue();
    }

    @Test
    void streamedBodyWithoutLengthIsCutOffWhileReading() {
        FilterChain readAll = (req, res) -> req.getInputStream().readAllBytes();

        assertThatThrownBy(() -> filter.doFilter(new NoLengthRequest(post(new byte[11])), response, readAll))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    private static MockHttpServletRequest post(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/repos");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body);
        return request;
    }

    /** MockHttpServletRequest always reports its content length; real chunked requests report -1. */
    static class NoLengthRequest extends jakarta.servlet.http.HttpServletRequestWrapper {
        NoLengthRequest(MockHttpServletRequest request) {
            super(request);
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public String getHeader(String name) {
            return "Content-Length".equalsIgnoreCase(name) ? null : super.getHeader(name);
        }
    }

    @Test
    void payloadTooLargeIsAnIOException() {
        assertThat(new PayloadTooLargeException(10)).isInstanceOf(IOException.class);
    }
}
