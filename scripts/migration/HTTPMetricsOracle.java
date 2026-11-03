import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/** 仅执行原框架观测约定与桶生成，不连接应用/数据库或读取宿主配置。 */
public class HTTPMetricsOracle {
    public static void main(String[] args) {
        var config = DistributionStatisticConfig.builder().percentilesHistogram(true)
                .minimumExpectedValue(1_000_000.0).maximumExpectedValue(30_000_000_000.0).build();
        for (double bound : config.getHistogramBuckets(true)) System.out.println("BOUND\t" + bound / 1e9);
        var convention = new DefaultServerRequestObservationConvention();
        for (String method : new String[]{"GET", "HEAD", "POST", "PATCH", "OPTIONS", "TRACE", "CONNECT", "PRIVATE"}) {
            for (int status : new int[]{200, 204, 302, 400, 401, 403, 404, 429, 500, 503}) {
                for (String pattern : new String[]{"", "/api/v1/fixture/{id}"}) {
                    var request = (HttpServletRequest) Proxy.newProxyInstance(HTTPMetricsOracle.class.getClassLoader(), new Class[]{HttpServletRequest.class}, (p,m,a) -> switch (m.getName()) {
                        case "getMethod" -> method; case "getRequestURI" -> "/private-id"; default -> null;
                    });
                    var response = (HttpServletResponse) Proxy.newProxyInstance(HTTPMetricsOracle.class.getClassLoader(), new Class[]{HttpServletResponse.class}, (p,m,a) -> m.getName().equals("getStatus") ? status : null);
                    var context = new ServerRequestObservationContext(request, response);
                    if (!pattern.isEmpty()) context.setPathPattern(pattern);
                    var tags = convention.getLowCardinalityKeyValues(context);
                    var line = new StringBuilder("CASE\t" + method + "\t" + status + "\t" + pattern);
                    tags.forEach(tag -> line.append("\t").append(tag.getKey()).append("=").append(tag.getValue()));
                    System.out.println(line);
                }
            }
        }
    }
}
