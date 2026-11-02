package com.personalos.common.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** 独立管理端口仅允许健康检查与指标读取，不共享业务接口的匿名权限。 */
@Configuration
@Profile("monitoring")
public class MonitoringSecurityConfig {
    @Bean
    @Order(0)
    SecurityFilterChain monitoringChain(HttpSecurity http,
            org.springframework.core.env.Environment environment) throws Exception {
        return http.securityMatcher(request -> request.getLocalPort() == environment.getProperty(
                        "local.management.port", Integer.class, 9091))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/actuator/prometheus", "/actuator/health").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
