package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ModelWindowProbeHttpTest {
    @Test
    void fillsMissingWindowsEvenWhenListContainsOtherModelsWindow() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            String body = exchange.getRequestURI().getPath().endsWith("/missing")
                    ? "{\"context_length\":64000}"
                    : "{\"data\":[{\"id\":\"known\",\"context_length\":8192},{\"id\":\"missing\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var service = new ModelListProbeService(new ObjectMapper());
            var result = service.probe(ModelProtocol.OPENAI, "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", List.of("missing"));
            assertThat(result.contextWindows()).containsEntry("known", 8192).containsEntry("missing", 64000);
        } finally { server.stop(0); }
    }

    @Test
    void ollamaQueriesShowWithOriginalModelTag() throws Exception {
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/show", exchange -> {
            request.set(exchange.getRequestMethod() + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"model_info\":{\"llama.context_length\":8192}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var service = new ModelListProbeService(new ObjectMapper());
            var result = service.probeWindow(ModelProtocol.OLLAMA, "http://127.0.0.1:" + server.getAddress().getPort(), null, "llama:8b");
            assertThat(result.window()).isEqualTo(8192);
            assertThat(request.get()).startsWith("POST ").contains("llama:8b");
        } finally { server.stop(0); }
    }
}
