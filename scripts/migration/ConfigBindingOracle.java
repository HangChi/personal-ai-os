import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PlaceholdersResolver;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.convert.ApplicationConversionService;

/** 只绑定传入的已解析canonical合成属性，不读取宿主环境或启动应用。 */
public class ConfigBindingOracle {
    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> { if (c < 32) result.append(String.format("\\u%04x", (int)c)); else result.append(c); }
            }
        }
        return result.append('"').toString();
    }
    private static String json(Object value) throws Exception {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        // 大于Go Duration范围时仍保留Java精确值，交由对照测试显式记录收紧。
        if (value instanceof Duration duration) return quote(java.math.BigInteger.valueOf(duration.getSeconds())
                .multiply(java.math.BigInteger.valueOf(1_000_000_000L)).add(java.math.BigInteger.valueOf(duration.getNano())).toString());
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value.getClass().isRecord()) {
            StringBuilder result = new StringBuilder("{");
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                if (result.length() > 1) result.append(',');
                result.append(quote(component.getName())).append(':').append(json(component.getAccessor().invoke(value)));
            }
            return result.append('}').toString();
        }
        throw new IllegalArgumentException("未支持的对照输出类型");
    }
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null;) {
            String[] fields = line.split("\t", -1);
            String prefix = fields[0];
            Class<?> type = Class.forName(fields[1]);
            Map<String,Object> properties = new TreeMap<>();
            for (int i = 2; i < fields.length; i += 2) properties.put(decode(fields[i]), decode(fields[i + 1]));
            var binder = new Binder(List.of(new MapConfigurationPropertySource(properties)),
                    PlaceholdersResolver.NONE, new ApplicationConversionService());
            String output;
            try { output = json(binder.bindOrCreate(prefix, Bindable.of(type))); }
            catch (Exception failure) { output = "error"; }
            System.out.println(Base64.getEncoder().encodeToString(output.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
