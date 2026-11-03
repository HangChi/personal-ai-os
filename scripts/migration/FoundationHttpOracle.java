import com.personalos.common.auth.*;
import com.personalos.common.exception.GlobalExceptionHandler;
import com.personalos.common.json.JsonCodec;
import com.personalos.common.health.*;
import com.personalos.common.config.AppProperties;
import com.personalos.common.ai.AiChatClientProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.personalos.common.api.ApiResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 真实嵌入式 Servlet/Security/Jackson/Validator/Actuator，只加载显式原基础源码。 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(excludeName = "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
@Import({SecurityConfig.class, MonitoringSecurityConfig.class, AuthController.class, CsrfController.class, HealthController.class, FoundationHttpOracle.UploadFixture.class, GlobalExceptionHandler.class})
public class FoundationHttpOracle {
    private static final AtomicBoolean DATABASE_UP = new AtomicBoolean(true);
    private static final AtomicBoolean MODEL_CONFIGURED = new AtomicBoolean(false);
    private static final AtomicInteger DATABASE_PROBES = new AtomicInteger();
    @Bean MockClock fixtureClock() { return new MockClock(); }
    @Bean MeterRegistry fixtureRegistry(MockClock clock) { return new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock); }
    @Bean AuthProperties authProperties(org.springframework.core.env.Environment env) {
        return new AuthProperties(env.getProperty("fixture.auth", Boolean.class, false), "admin", "fixture-password", 1000, Duration.ofMinutes(1));
    }
    @Bean AuthService authService(AuthProperties properties, PasswordEncoder encoder) { return new AuthService(properties, encoder); }
    @Bean JsonCodec jsonCodec(ObjectMapper mapper) { return new JsonCodec(mapper); }
    @Bean HealthIndicator dbHealthIndicator() {
        return () -> (DATABASE_UP.get() ? Health.up() : Health.down()).withDetail("database", "fixture").build();
    }
    @Bean HealthService fixtureHealthService() {
        var jdbc = new JdbcTemplate() {
            @Override public <T> T queryForObject(String sql, Class<T> type) {
                if (!sql.equals("SELECT 1") || type != Integer.class) throw new IllegalStateException("探测越界");
                DATABASE_PROBES.incrementAndGet();
                if (!DATABASE_UP.get()) throw new DataAccessResourceFailureException("synthetic-unavailable");
                return type.cast(1);
            }
        };
        var model = new AiChatClientProvider() {
            @Override public boolean isConfigured() { return MODEL_CONFIGURED.get(); }
        };
        return new HealthService(new AppProperties("fixture", "test"), jdbc, model);
    }
    /** 只验证真实Servlet参数绑定/容器限制，不替代附件或知识库业务实现。 */
    @RestController
    static class UploadFixture {
        @PostMapping("/api/v1/fixture-upload")
        ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws Exception { return describe(file); }
        @PostMapping(value = "/api/v1/fixture-part", consumes = "multipart/form-data")
        ApiResponse<Map<String, Object>> part(@RequestPart("file") MultipartFile file) throws Exception { return describe(file); }
        private ApiResponse<Map<String, Object>> describe(MultipartFile file) throws Exception {
            var data = new LinkedHashMap<String, Object>();
            data.put("filename", file.getOriginalFilename());
            data.put("contentType", file.getContentType());
            data.put("size", file.getSize());
            data.put("empty", file.isEmpty());
            data.put("bytes", Base64.getEncoder().encodeToString(file.getBytes()));
            return ApiResponse.ok(data);
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // 不读取宿主属性/环境、工作目录 YAML 或项目 DotEnv 处理器。
        var environment = new StandardEnvironment() {
            @Override protected void customizePropertySources(org.springframework.core.env.MutablePropertySources sources) {}
        };
        var settings = new LinkedHashMap<String, Object>();
        settings.put("spring.config.location", "optional:classpath:/fixture-no-config.yml");
        settings.put("server.address", "127.0.0.1");
        settings.put("server.port", "0");
        settings.put("spring.main.banner-mode", "off");
        settings.put("logging.level.root", "OFF");
        settings.put("management.endpoints.web.exposure.include", "health,info,metrics");
        settings.put("management.endpoint.health.show-details", "when_authorized");
        settings.put("management.health.diskspace.enabled", "false");
        settings.put("management.info.env.enabled", "true");
        settings.put("info.app.name", "fixture");
        settings.put("info.app.version", "test");
        settings.put("fixture.auth", args[0]);
        settings.put("spring.servlet.multipart.max-file-size", "64B");
        settings.put("spring.servlet.multipart.max-request-size", "1024B");
        settings.put("app.security.csrf-enabled", args[1]);
        if (Boolean.parseBoolean(args[2])) {
            settings.put("spring.profiles.active", "monitoring");
            settings.put("management.server.port", "0");
            settings.put("management.server.address", "127.0.0.1");
            settings.put("management.endpoints.web.exposure.include", "health,prometheus");
        }
        environment.getPropertySources().addFirst(new MapPropertySource("fixture-only", settings));
        var application = new SpringApplication(FoundationHttpOracle.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        var mapper = JsonMapper.builder().build();
        try (ConfigurableApplicationContext context = application.run()) {
            int port = context.getEnvironment().getProperty("local.server.port", Integer.class);
            int management = context.getEnvironment().getProperty("local.management.port", Integer.class, port);
            try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    Map<String, Object> input = mapper.readValue(line, Map.class);
                    DATABASE_UP.set(!Boolean.TRUE.equals(input.get("databaseDown")));
                    MODEL_CONFIGURED.set(Boolean.TRUE.equals(input.get("modelConfigured")));
                    DATABASE_PROBES.set(0);
                    // 只合成原限流器相同名称/标签的样本，端点序列化/过滤/聚合由真实Actuator执行。
                    var registry = context.getBean(MeterRegistry.class);
                    registry.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("personalos.rate.limit.")).toList().forEach(registry::remove);
                    registry.timer("personalos.rate.limit.acquire", "backend", "postgres").record(Duration.ofMillis(10));
                    registry.timer("personalos.rate.limit.acquire", "backend", "postgres").record(Duration.ofMillis(20));
                    registry.timer("personalos.rate.limit.acquire", "backend", "redis").record(Duration.ofMillis(30));
                    registry.counter("personalos.rate.limit.errors", "backend", "redis").increment(2);
                    if (Boolean.TRUE.equals(input.get("metricExpired"))) context.getBean(MockClock.class).add(Duration.ofMinutes(3));
                    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
                    try (var client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build()) {
                        String origin = "http://127.0.0.1:" + (Boolean.TRUE.equals(input.get("management")) ? management : port);
                        String token = null;
                        if (Boolean.TRUE.equals(input.get("csrf"))) {
                            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
                            Map<String, Object> payload = mapper.readValue(response.body(), Map.class);
                            token = (String) ((Map<String, Object>) payload.get("data")).get("token");
                        }
                        if (Boolean.TRUE.equals(input.get("login"))) {
                            var login = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login")).header("Content-Type", "application/json");
                            if (token != null) login.header("X-CSRF-TOKEN", token);
                            var response = client.send(login.POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"fixture-password\"}")).build(), HttpResponse.BodyHandlers.ofString());
                            if (response.statusCode() != 200) throw new IllegalStateException("合成登录失败");
                        }
                        var request = HttpRequest.newBuilder(URI.create(origin + input.get("path")));
                        if (input.containsKey("contentType")) request.header("Content-Type", (String) input.get("contentType"));
                        if (input.containsKey("accept")) request.header("Accept", (String) input.get("accept"));
                        if (token != null) request.header("X-CSRF-TOKEN", token);
                        if (Boolean.TRUE.equals(input.get("badCsrf"))) request.setHeader("X-CSRF-TOKEN", "fixture-invalid-token");
                        request.method((String) input.get("method"), input.containsKey("body") ? HttpRequest.BodyPublishers.ofString((String) input.get("body")) : HttpRequest.BodyPublishers.noBody());
                        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                        var output = new LinkedHashMap<String, Object>();
                        output.put("name", input.get("name"));
                        output.put("status", response.statusCode());
                        output.put("contentType", response.headers().firstValue("content-type").orElse(""));
                        output.put("allow", response.headers().firstValue("allow").orElse(""));
                        output.put("body", response.body());
                        if (input.get("path").equals("/api/v1/health")) output.put("databaseProbes", DATABASE_PROBES.get());
                        System.out.println("CASE\t" + mapper.writeValueAsString(output));
                    }
                }
            }
        }
    }
}
