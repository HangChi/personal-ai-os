package com.personalos.common.web;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.fail;
import com.personalos.common.json.JsonCodec;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.ObjectMapper;

class ModelConcurrencyFilterTest {
    private final ModelConcurrencyFilter filter = new ModelConcurrencyFilter(1, new JsonCodec(new ObjectMapper()));
    private MockHttpServletRequest request() {
        var r = new MockHttpServletRequest("POST", "/api/v1/knowledge/ask");
        r.setAsyncSupported(true);
        return r;
    }
    private void assertAvailable() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(), response, (r, s) -> s.getWriter().write("ok"));
        assertThat(response.getContentAsString()).isEqualTo("ok");
    }
    @Test
    void asyncPermitIsHeldUntilCompletionAndReleasedOnlyOnce() throws Exception {
        var first = request();
        filter.doFilter(first, new MockHttpServletResponse(), (r, s) -> r.startAsync());
        var blocked = new MockHttpServletResponse();
        filter.doFilter(request(), blocked, (r, s) -> fail("并发超限不应执行"));
        assertThat(blocked.getStatus()).isEqualTo(429);
        first.getAsyncContext().complete();
        assertAvailable();
    }
    @Test
    void failuresAndSyncCompletionReleasePermit() throws Exception {
        assertThatThrownBy(() -> filter.doFilter(request(), new MockHttpServletResponse(),
                (r, s) -> { throw new IOException("失败"); })).isInstanceOf(IOException.class);
        assertAvailable();
        assertAvailable();
    }
    @Test
    void coversInferenceRoutesAndLeavesLocalQueriesAlone() {
        for (String path : new String[]{"/api/v1/knowledge/search", "/api/v1/knowledge/ask",
                "/api/v1/chat/transcribe", "/api/v1/chat/%75ploads"}) {
            assertThat(ModelRequestPaths.matches(new MockHttpServletRequest("POST", path))).isTrue();
        }
        assertThat(ModelRequestPaths.matches(new MockHttpServletRequest("PUT", "/api/v1/knowledge/documents/id"))).isTrue();
        assertThat(ModelRequestPaths.matches(new MockHttpServletRequest("GET", "/api/v1/tasks"))).isFalse();
        assertThat(ModelRequestPaths.matches(new MockHttpServletRequest("POST", "/api/v1/tasks"))).isFalse();
    }
}
