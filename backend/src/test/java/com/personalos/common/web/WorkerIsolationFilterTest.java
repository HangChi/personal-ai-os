package com.personalos.common.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.config.RuntimeRole;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WorkerIsolationFilterTest {
    @Test void workerRejectsBusinessRequestsBeforeCallingDownstream() throws Exception {
        var chain=mock(FilterChain.class);
        var response=new MockHttpServletResponse();
        new WorkerIsolationFilter(new RuntimeRole("index")).doFilter(
                new MockHttpServletRequest("POST","/api/v1/knowledge/jobs"),response,chain);
        assertThat(response.getStatus()).isEqualTo(404);
        verifyNoInteractions(chain);
    }
    @Test void workerHealthAndApiRequestsRemainAccessibleOnTheirRespectiveRoles() throws Exception {
        for (var item : java.util.List.of(new String[]{"review","/actuator/health"},new String[]{"api","/api/v1/knowledge/jobs"})) {
            var chain=mock(FilterChain.class);
            new WorkerIsolationFilter(new RuntimeRole(item[0])).doFilter(
                    new MockHttpServletRequest("GET",item[1]),new MockHttpServletResponse(),chain);
            verify(chain).doFilter(any(),any());
        }
    }
}
