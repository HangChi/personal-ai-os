package com.personalos.support;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 测试用的轻量 HTTP 客户端。
 *
 * <p>用 JDK 自带的 {@code java.net.http.HttpClient} 而不是 {@code TestRestTemplate} /
 * {@code MockMvc}：不依赖任何会随框架版本变化的测试切片 API，且 SSE 逐帧读取本来就是
 * 流式响应的原生形态。测试写的是「真实 HTTP 行为」，不是「框架的测试封装」。
 */
public class HttpTestClient {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;

    public HttpTestClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    /** 普通响应 */
    public record Response(int status, String body) {
    }

    /** SSE 事件 */
    public record SseEvent(String name, String data) {
    }

    public Response get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
    }

    public Response post(String path, String jsonBody) throws Exception {
        return post(path, jsonBody, Map.of());
    }

    public Response post(String path, String jsonBody, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json");
        headers.forEach(builder::header);
        return send(builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build());
    }

    public Response patch(String path, String jsonBody, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json");
        headers.forEach(builder::header);
        return send(builder.method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody)).build());
    }

    public Response delete(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
        headers.forEach(builder::header);
        return send(builder.DELETE().build());
    }

    /**
     * 发起 SSE 请求并逐帧解析。
     *
     * <p>解析遵循 SSE 规范：空行是事件边界，{@code event:} 指定名称，多行 {@code data:} 拼接。
     */
    public List<SseEvent> sse(String path, String jsonBody, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream");
        headers.forEach(builder::header);

        HttpResponse<Stream<String>> response = client.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build(),
                HttpResponse.BodyHandlers.ofLines());

        List<SseEvent> events = new ArrayList<>();
        if (response.statusCode() != 200) {
            try (Stream<String> lines = response.body()) {
                lines.forEach(ignored -> { });
            }
            return events;
        }

        String currentEvent = null;
        List<String> dataLines = new ArrayList<>();
        try (Stream<String> lines = response.body()) {
            for (String rawLine : (Iterable<String>) lines::iterator) {
                String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
                if (line.isEmpty()) {
                    if (!dataLines.isEmpty()) {
                        events.add(new SseEvent(currentEvent, String.join("\n", dataLines)));
                        dataLines.clear();
                    }
                    currentEvent = null;
                } else if (line.startsWith("event:")) {
                    currentEvent = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    dataLines.add(line.substring("data:".length()).trim());
                }
            }
        }
        if (!dataLines.isEmpty()) {
            events.add(new SseEvent(currentEvent, String.join("\n", dataLines)));
        }
        return events;
    }

    /** SSE 请求并返回状态码（用于断言失败路径不是 200） */
    public int sseStatus(String path, String jsonBody) throws Exception {        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            lines.forEach(ignored -> { });
        }
        return response.statusCode();
    }

    private Response send(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    /**
     * multipart/form-data 上传。
     *
     * <p>手写 multipart 编码而不是引入测试专用的 HTTP 客户端库：格式本身很简单，
     * 而且这样上传走的是**真实**的网络路径（含 Spring 的 multipart 解析与大小限制），
     * 不是某个库替我们拼好的请求。
     *
     * @param path       接口路径
     * @param filename   上传的文件名（决定服务端看到的扩展名）
     * @param content    文件字节
     * @param formFields 额外的普通表单字段
     */
    public Response upload(String path, String filename, byte[] content, Map<String, String> formFields)
            throws Exception {
        String boundary = "----personalos" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        for (Map.Entry<String, String> field : formFields.entrySet()) {
            writeUtf8(body, "--" + boundary + "\r\n");
            writeUtf8(body, "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n");
            writeUtf8(body, field.getValue() + "\r\n");
        }

        writeUtf8(body, "--" + boundary + "\r\n");
        writeUtf8(body, "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n");
        writeUtf8(body, "Content-Type: application/octet-stream\r\n\r\n");
        body.write(content);
        writeUtf8(body, "\r\n--" + boundary + "--\r\n");

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return send(request);
    }

    private static void writeUtf8(ByteArrayOutputStream target, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        target.write(bytes, 0, bytes.length);
    }
}
