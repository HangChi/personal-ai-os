package com.personalos.common.json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON 序列化的唯一入口。
 *
 * <h2>为什么必须封装</h2>
 * 本项目的 classpath 上**同时存在两个 Jackson 大版本**（实测 {@code mvn dependency:list}）：
 * <ul>
 *   <li>{@code tools.jackson.core:jackson-databind:3.1.5} —— Jackson 3，Spring Boot 4 主用；</li>
 *   <li>{@code com.fasterxml.jackson.core:jackson-databind:2.21.5} —— Jackson 2，
 *       由 Spring AI 的 json-schema 模块等传递引入。</li>
 * </ul>
 * 两者各有一个同名但不同包的 {@code ObjectMapper}，散落注入极易混淆（且编译期不报错、
 * 只在运行期表现为某些字段莫名其妙丢失）。集中到本类后，序列化策略只有一处可改。
 *
 * <p>本类使用 Jackson 3 的 {@link ObjectMapper}（即 Spring Boot 4 自动配置的那个）。
 */
@Component
public class JsonCodec {

    private final ObjectMapper objectMapper;

    public JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 序列化为 JSON 文本。
     *
     * <p>Jackson 3 的异常是非受检的，这里仍统一包成 {@link IllegalStateException}，
     * 以免未来版本调整异常语义时影响到调用方。
     */
    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("JSON 序列化失败：" + value.getClass().getSimpleName(), ex);
        }
    }

    /**
     * 反序列化。
     */
    public <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("JSON 反序列化失败：" + type.getSimpleName(), ex);
        }
    }

    /**
     * 计算一个对象的 JSON 表示的 SHA-256 摘要。
     *
     * <p>用于幂等判定：同一个幂等键 + 不同摘要 = 冲突。
     *
     * <p><b>前提</b>：调用方传入的必须是**字段顺序稳定**的对象（record 或按固定顺序构建的
     * LinkedHashMap）。顺序不稳定的 Map 会产生不同摘要，从而把「重复请求」误判成「冲突」。
     */
    public String digestOf(Object value) {
        return sha256Hex(toJson(value));
    }

    /**
     * 计算字符串的 SHA-256 十六进制摘要。
     */
    public static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", ex);
        }
    }
}
