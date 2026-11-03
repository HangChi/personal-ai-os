import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import com.personalos.common.config.ProductionSecurityValidator;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

/** 仅反射独立编译的基线配置record和加载传入夹具YAML；不启动Spring/Binder。 */
public class ConfigInventoryOracle {
    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static void emit(String... fields) {
        System.out.println(String.join("\t", Arrays.stream(fields).map(ConfigInventoryOracle::encode).toList()));
    }
    private static String kebab(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }
    private static void record(String root, String prefix, Class<?> type, int depth) throws Exception {
        if (depth > 10 || !type.isRecord()) throw new IllegalArgumentException("配置record结构不受支持");
        RecordComponent[] components = type.getRecordComponents();
        var constructor = type.getDeclaredConstructor(Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
        for (int i = 0; i < components.length; i++) {
            var component = components[i];
            String property = prefix + "." + kebab(component.getName());
            Type generic = component.getGenericType();
            DefaultValue defaults = constructor.getParameters()[i].getAnnotation(DefaultValue.class);
            String defaultValue = defaults == null ? "" : String.join("\u001f", defaults.value());
            emit("FIELD", root, property, generic.getTypeName(), defaults == null ? "absent" : "present", defaultValue);
            if (component.getType().isRecord()) record(root, property, component.getType(), depth + 1);
            if (Map.class.isAssignableFrom(component.getType()) && generic instanceof ParameterizedType parameterized) {
                Type mapped = parameterized.getActualTypeArguments()[1];
                if (mapped instanceof Class<?> child && child.isRecord()) record(root, property + ".*", child, depth + 1);
            }
        }
    }
    private static void cookieResolution(Map<String,List<PropertySource<?>>> yaml) {
        for (String scenario : List.of("local-default", "local-cookie-alias", "prod-cookie-alias", "prod-canonical-env")) {
            // 覆盖customize避免读取宿主System properties/environment；只放入下方合成来源。
            var environment = new StandardEnvironment() {
                @Override protected void customizePropertySources(MutablePropertySources sources) {}
            };
            environment.setConversionService(new ApplicationConversionService());
            for (var source : yaml.get("application.yml")) environment.getPropertySources().addFirst(source);
            if (scenario.startsWith("prod")) {
                for (var source : yaml.get("application-prod.yml")) environment.getPropertySources().addFirst(source);
            }
            Map<String,Object> synthetic = new HashMap<>();
            synthetic.put("AUTH_PASSWORD", "fixture-password-16");
            synthetic.put("POSTGRES_PASSWORD", "fixture-non-default-database");
            if (!scenario.equals("local-default")) synthetic.put("SESSION_COOKIE_SECURE", "true");
            if (scenario.equals("prod-canonical-env")) synthetic.put("SERVER_SERVLET_SESSION_COOKIE_SECURE", "true");
            environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("synthetic", synthetic));
            boolean declared = environment.getProperty("spring.servlet.session.cookie.secure", Boolean.class, false);
            boolean consumed = environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false);
            String result = "accepted";
            try {
                new ProductionSecurityValidator(environment.getProperty("app.security.production-mode", Boolean.class, false),
                        environment.getProperty("app.auth.enabled", Boolean.class, false),
                        environment.getProperty("app.security.csrf-enabled", Boolean.class, false), consumed,
                        environment.getProperty("app.auth.password", ""), environment.getProperty("spring.datasource.password", ""));
            } catch (IllegalStateException ex) { result = ex.getMessage(); }
            emit("RESOLVE", scenario, Boolean.toString(declared), Boolean.toString(consumed), result);
        }
    }
    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[0]);
        var loader = new YamlPropertySourceLoader();
        Map<String,List<PropertySource<?>>> yaml = new HashMap<>();
        try (var files = java.nio.file.Files.list(folder)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                var sources = loader.load(file.getFileName().toString(), new FileSystemResource(file));
                yaml.put(file.getFileName().toString(), sources);
                for (int document = 0; document < sources.size(); document++) {
                    var source = (EnumerablePropertySource<?>) sources.get(document);
                    String[] keys = source.getPropertyNames();
                    Arrays.sort(keys);
                    for (String key : keys) {
                        Object value = source.getProperty(key);
                        emit("YAML", file.getFileName().toString(), Integer.toString(document), key,
                                value == null ? "null" : value.getClass().getName(), value == null ? "" : value.toString());
                    }
                }
            }
        }
        cookieResolution(yaml);
        for (int i = 1; i < args.length; i++) {
            Class<?> type = Class.forName(args[i], false, ConfigInventoryOracle.class.getClassLoader());
            ConfigurationProperties annotation = type.getAnnotation(ConfigurationProperties.class);
            if (annotation == null) throw new IllegalArgumentException("缺少ConfigurationProperties");
            emit("CONFIG", args[i], annotation.prefix());
            record(args[i], annotation.prefix(), type, 0);
        }
    }
}
