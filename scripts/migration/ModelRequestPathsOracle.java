import com.personalos.common.web.ModelRequestPaths;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 无监听、无数据库的固定 Java 路径判定对照入口。输入三列 Base64，输出匹配或异常类型。 */
public final class ModelRequestPathsOracle {
    public static void main(String[] args) throws Exception {
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = reader.readLine()) != null;) {
            String[] fields = line.split("\t", -1);
            String method = decode(fields[0]), uri = decode(fields[1]), context = decode(fields[2]);
            HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                    ModelRequestPathsOracle.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                    (proxy, called, values) -> switch (called.getName()) {
                        case "getMethod" -> method;
                        case "getRequestURI" -> uri;
                        case "getContextPath" -> context;
                        default -> throw new UnsupportedOperationException(called.getName());
                    });
            try {
                System.out.println(ModelRequestPaths.matches(request));
            } catch (IllegalArgumentException exception) {
                // 不输出异常消息，原路径不应成为诊断日志中的隐私副本。
                System.out.println("error:" + exception.getClass().getSimpleName());
            }
        }
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
