import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** 只加载脚本临时目录内的七个原YAML；没有Spring应用、真实.env或宿主属性来源。 */
public class ConfigDataOracle {
    private static String decode(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        List<String> keys = java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[1]), StandardCharsets.UTF_8);
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null;) {
            StandardEnvironment environment = new StandardEnvironment() {
                @Override protected void customizePropertySources(MutablePropertySources sources) { }
            };
            String[] fields = line.split("\t", -1);
            Map<String,Object> overrides = new TreeMap<>(), system = new TreeMap<>(), dotenv = new TreeMap<>();
            for (int i = 0; i < fields.length;) {
                String source = fields[i++];
                Map<String,Object> target = switch (source) { case "MAP" -> overrides; case "ENV" -> system; case "DOT" -> dotenv; default -> throw new IllegalArgumentException(); };
                while (i < fields.length && !List.of("MAP", "ENV", "DOT").contains(fields[i])) {
                    target.put(decode(fields[i]), decode(fields[i+1])); i += 2;
                }
            }
            // 显式唯一位置，不搜索当前目录或父目录；配置输入不能覆盖此位置。
            overrides.put("spring.config.location", "file:" + args[0] + "/");
            environment.getPropertySources().addLast(new MapPropertySource("synthetic-overrides", overrides));
            environment.getPropertySources().addLast(new SystemEnvironmentPropertySource("synthetic-systemEnvironment", system));
            String output;
            try {
                ConfigDataEnvironmentPostProcessor.applyTo(environment);
                // 原DotEnv处理器无Ordered/Order，晚于有显式ORDER的ConfigData执行。
                // 因此dotenv不能参与已经结束的profile文件发现，只能参与最终属性/占位符解析。
                environment.getPropertySources().addLast(new SystemEnvironmentPropertySource("dotenv-systemEnvironment", dotenv));
                ConfigurationPropertySources.attach(environment);
                Map<String,Object> properties = new TreeMap<>();
                for (String key : keys) {
                    try { properties.put(key, environment.getProperty(key)); }
                    catch (Exception failure) { properties.put(key, Map.of("error", true)); }
                }
                Map<String,Object> result = new TreeMap<>();
                result.put("profiles", List.of(environment.getActiveProfiles()));
                result.put("defaults", List.of(environment.getDefaultProfiles()));
                result.put("properties", properties);
                output = json(result);
            } catch (Exception failure) { output = "error"; }
            System.out.println(Base64.getEncoder().encodeToString(output.getBytes(StandardCharsets.UTF_8)));
        }
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Map<?,?> map) {
            List<String> entries = new ArrayList<>();
            for (var entry : map.entrySet()) entries.add(json(entry.getKey().toString()) + ":" + json(entry.getValue()));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof List<?> values) {
            List<String> entries = new ArrayList<>();
            for (Object child : values) entries.add(json(child));
            return "[" + String.join(",", entries) + "]";
        }
        String text = value.toString();
        StringBuilder result = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> result.append("\\\""); case '\\' -> result.append("\\\\");
                default -> { if (c < 32) result.append(String.format("\\u%04x", (int)c)); else result.append(c); }
            }
        }
        return result.append('"').toString();
    }
}
