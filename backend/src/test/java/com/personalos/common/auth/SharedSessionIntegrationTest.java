package com.personalos.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SharedSessionIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2.1-alpine")
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "session-test-password");

    @Test void cookieReadsSecurityContextAcrossInstancesAndLogoutInvalidatesBoth() throws Exception {
        try (var first = context(); var second = context()) {
            var login = new MockHttpServletResponse();
            filter(first).doFilter(request(), login, (req,res) ->
                    ((HttpServletRequest) req).getSession().setAttribute("SPRING_SECURITY_CONTEXT",
                            new SecurityContextImpl(new UsernamePasswordAuthenticationToken("operator", null, java.util.List.of()))));
            Cookie cookie = login.getCookie("SESSION");
            assertThat(cookie).isNotNull();
            var authenticated = request();
            authenticated.setCookies(cookie);
            filter(second).doFilter(authenticated, new MockHttpServletResponse(), (req,res) -> {
                var session = ((HttpServletRequest) req).getSession(false);
                var security = (SecurityContextImpl) session.getAttribute("SPRING_SECURITY_CONTEXT");
                assertThat(security.getAuthentication().getName()).isEqualTo("operator");
                session.invalidate();
            });
            var oldCookie = request();
            oldCookie.setCookies(cookie);
            filter(first).doFilter(oldCookie, new MockHttpServletResponse(), (req,res) ->
                    assertThat(((HttpServletRequest) req).getSession(false)).isNull());
        }
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/v1/auth/me");
    }

    private SessionRepositoryFilter<?> filter(AnnotationConfigWebApplicationContext context) {
        return context.getBean(SessionRepositoryFilter.class);
    }

    private AnnotationConfigWebApplicationContext context() {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertyValues.of("app.shared-session.enabled=true", "app.shared-session.namespace=test:sessions",
                "app.shared-session.uri=redis://:session-test-password@" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379))
                .applyTo(context);
        context.register(SharedSessionConfig.class);
        context.refresh();
        return context;
    }
}
