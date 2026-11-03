package config

import "strings"

// ValidateFoundationExtensions只限制已经接管的基础设施配置面；后续业务配置由对应阶段消费。
// 未实现的Hikari/Servlet/Tomcat/Flyway/管理扩展在创建资源前拒绝，不能静默失效。
func (p *Properties) ValidateFoundationExtensions() error {
	if p == nil {
		return &propertyError{"foundation", ErrInvalidProperty}
	}
	allowed := map[string]bool{}
	for _, key := range []string{
		"spring.datasource.url", "spring.datasource.username", "spring.datasource.password", "spring.datasource.hikari.maximum-pool-size", "spring.datasource.hikari.connection-timeout",
		"server.port", "server.servlet.session.timeout", "server.servlet.session.cookie.name", "server.servlet.session.cookie.path", "server.servlet.session.cookie.domain", "server.servlet.session.cookie.secure", "server.servlet.session.cookie.http-only", "server.servlet.session.cookie.same-site", "server.servlet.session.cookie.max-age",
		"spring.servlet.session.cookie.secure", "spring.servlet.session.cookie.http-only", "spring.servlet.session.cookie.same-site",
		"spring.servlet.multipart.max-file-size", "spring.servlet.multipart.max-request-size", "spring.servlet.multipart.location", "spring.servlet.multipart.enabled", "spring.servlet.multipart.file-size-threshold", "spring.servlet.multipart.resolve-lazily", "spring.servlet.multipart.strict-servlet-compliance",
		"management.server.port", "management.endpoints.web.exposure.include", "management.endpoints.web.exposure.exclude", "management.endpoint.health.show-details", "management.endpoint.health.probes.enabled", "management.info.env.enabled", "management.health.diskspace.enabled", "management.health.ssl.enabled", "management.health.ping.enabled",
		"management.metrics.distribution.percentiles-histogram.http.server.requests", "management.metrics.distribution.percentiles-histogram.personalos.embedding.duration", "management.metrics.distribution.percentiles-histogram.personalos.knowledge.recall", "management.metrics.distribution.percentiles-histogram.personalos.knowledge.rag.stage", "management.metrics.enable.http.server.requests",
		"logging.structured.format.console", "logging.level.root",
	} {
		allowed[compactKey(key)] = true
	}
	fixed := map[string]string{
		"spring.datasource.hikari.pool-name":  "personalos-pool",
		"spring.datasource.driver-class-name": "org.postgresql.Driver",
		"spring.flyway.enabled":               "true", "spring.flyway.locations": "classpath:db/migration", "spring.flyway.baseline-on-migrate": "true",
		"spring.jpa.open-in-view": "false", "spring.jpa.hibernate.ddl-auto": "validate", "spring.jpa.properties.hibernate.jdbc.time_zone": "UTC",
		"server.shutdown": "graceful", "server.address": "127.0.0.1",
		"logging.pattern.console": BaselineConsolePattern,
	}
	for key, expected := range fixed {
		allowed[compactKey(key)] = true
		if value, found, err := p.Get(key); err != nil {
			return err
		} else if found && value != expected {
			return &propertyError{key, ErrInvalidProperty}
		}
	}
	for _, source := range p.sources {
		for key := range source.Values {
			uniform := compactKey(key)
			for _, family := range []string{"springdatasource", "springflyway", "springjpa", "springservlet", "server", "management", "logging"} {
				if strings.HasPrefix(uniform, family) && !allowed[uniform] {
					return &propertyError{family + "（未支持的扩展属性）", ErrInvalidProperty}
				}
			}
		}
	}
	return nil
}

func compactKey(key string) string {
	return strings.ToLower(strings.NewReplacer(".", "", "-", "", "_", "").Replace(key))
}
