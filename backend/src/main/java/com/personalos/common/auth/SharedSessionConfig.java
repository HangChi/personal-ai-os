package com.personalos.common.auth;

import java.time.Duration;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "app.shared-session.enabled", havingValue = "true")
@EnableRedisHttpSession(redisNamespace = "${app.shared-session.namespace}")
public class SharedSessionConfig {
    @Bean
    LettuceConnectionFactory sessionRedisConnection(@Value("${app.shared-session.uri}") String address) {
        URI uri;
        try {
            uri = URI.create(address);
            if (uri.getHost() == null || !java.util.Set.of("redis", "rediss").contains(uri.getScheme()) || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid dedicated session Redis endpoint");
        }
        var server = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort() < 0 ? 6379 : uri.getPort());
        String path = uri.getPath();
        if (path != null && path.length() > 1) server.setDatabase(Integer.parseInt(path.substring(1)));
        if (uri.getUserInfo() != null) {
            String[] credentials = uri.getUserInfo().split(":", 2);
            if (credentials.length == 2) {
                if (!credentials[0].isBlank()) server.setUsername(credentials[0]);
                server.setPassword(RedisPassword.of(credentials[1]));
            } else {
                server.setPassword(RedisPassword.of(credentials[0]));
            }
        }
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2));
        if ("rediss".equals(uri.getScheme())) client.useSsl();
        return new LettuceConnectionFactory(server, client.build());
    }

    @Bean
    DefaultCookieSerializer sessionCookieSerializer(@Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        var cookie = new DefaultCookieSerializer();
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie(secure);
        cookie.setSameSite("Lax");
        return cookie;
    }
}
