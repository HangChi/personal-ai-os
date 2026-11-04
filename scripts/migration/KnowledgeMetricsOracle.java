import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.concurrent.TimeUnit;

public final class KnowledgeMetricsOracle {
    public static void main(String[] arguments) {
        var meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            meters.counter("personalos.knowledge.jobs.attempts", "outcome", "success").increment();
            meters.counter("personalos.knowledge.rag.results", "status", "READY").increment();
            meters.counter("personalos.knowledge.rag.tokens", "kind", "input").increment(17);
            meters.counter("personalos.knowledge.rag.rerank.tokens").increment(9);
            meters.timer("personalos.knowledge.jobs.duration").record(2, TimeUnit.SECONDS);
            Timer.builder("personalos.knowledge.rag.stage").tags("stage", "recall", "result", "OK")
                .publishPercentileHistogram().register(meters).record(250, TimeUnit.MILLISECONDS);
            Timer.builder("personalos.knowledge.recall").tags("stage", "query_embedding")
                .publishPercentileHistogram().register(meters).record(10, TimeUnit.MILLISECONDS);
            meters.gauge("personalos.knowledge.jobs.active", 2);
            System.out.print(meters.scrape());
        } finally { meters.close(); }
    }
}
