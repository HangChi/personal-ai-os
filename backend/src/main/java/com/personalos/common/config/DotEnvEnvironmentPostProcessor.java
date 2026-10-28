package com.personalos.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 从项目根目录的 {@code .env} 读入变量，作为 Spring 的 lowest-priority 属性源。
 *
 * <h2>为什么需要它</h2>
 * 本项目的数据库连接、模型端点、API Key 等可变项**都写在 {@code .env} 里**，
 * {@code application.yml} 只引用 {@code ${OPENAI_API_KEY:}} 这样的占位符。
 * 但 Spring Boot 本身不读 {@code .env}（那是 Docker Compose 的约定），
 * 于是「用 {@code mvn spring-boot:run} 直接起后端」时所有占位符都解析成空串：
 * <ul>
 *   <li>数据库回落到 {@code localhost:55432}（恰好与默认一致，掩盖了问题）；</li>
 *   <li>模型端点回落到 {@code https://api.openai.com}；</li>
 *   <li>API Key 回落到空串 —— 表现为「设置页说留空会回落 .env，实际并没有」。</li>
 * </ul>
 * 这套回落是**静默**的：启动一切正常，只有真正发请求时才 401 或连错端点。
 * 2026-10-28 就因为这一点，界面上三套配置全显示「未设置密钥」时无法判断
 * 「密钥真丢了」还是「环境变量根本没被读到」。
 *
 * <h2>为什么是「最低优先级」而不是覆盖</h2>
 * 用 {@link MapPropertySource} 追加到 {@code propertySources} 的**末尾**，
 * 于是命令行参数、系统环境变量、{@code application.yml} 都仍然可以覆盖它。
 * 这符合「{@code .env} 是开发期默认值、不是最终真相」的定位：
 * <pre>
 *   命令行 -D / --xxx  &gt;  真实系统环境变量  &gt;  application.yml  &gt;  .env
 * </pre>
 * 反过来（让 {@code .env} 覆盖系统环境变量）会让容器里注入的环境变量失效，
 * 那才是更危险的方向。
 *
 * <h2>为什么键名不做前缀转换</h2>
 * {@code .env} 里的键就是 {@code OPENAI_API_KEY} 这种原生名字，
 * {@code application.yml} 也用 {@code ${OPENAI_API_KEY:}} 引用它。
 * Docker Compose 把 {@code .env} 注入容器时同样不做前缀转换 ——
 * 这里保持一致，两条路径读到的变量名完全相同。
 *
 * <h2>找不到文件时不报错</h2>
 * 公网部署时通常只注入环境变量、不放 {@code .env} 文件。缺文件是正常情形，
 * 只在 debug 级别留痕。
 */
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DotEnvEnvironmentPostProcessor.class);

    /** 属性源名字，便于在 {@code /actuator/env} 里辨认 */
    private static final String SOURCE_NAME = "dotenv";

    /** 从当前工作目录往上找的层数上限（mvn 在 backend/ 下跑，.env 在上一层） */
    private static final int MAX_PARENT_LOOKUPS = 3;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path dotEnv = locate();
        if (dotEnv == null) {
            log.debug("未找到 .env，跳过；将以系统环境变量与 application.yml 为准");
            return;
        }
        Map<String, Object> values;
        try {
            values = parse(Files.readAllLines(dotEnv, StandardCharsets.UTF_8));
        } catch (IOException cause) {
            // 读失败不能拖垮启动：没有 .env 时应用仍应能跑（走 yml 默认值）
            log.warn("读取 {} 失败，忽略该文件：{}", dotEnv, cause.getMessage());
            return;
        }
        if (values.isEmpty()) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, values));
        log.info("已加载 {} 中的 {} 个变量（最低优先级，可被系统环境变量覆盖）",
                dotEnv.toAbsolutePath().normalize(), values.size());
    }

    /**
     * 从工作目录向上查找 {@code .env}。
     *
     * <p>必须往上找：后端通常在 {@code backend/} 子目录下用 Maven 启动，
     * 而 {@code .env} 在仓库根。只认当前目录会让「从 backend 起」和
     * 「从根起」两种方式行为不一致 —— 这类环境相关的差异最难排查。
     */
    private Path locate() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i <= MAX_PARENT_LOOKUPS && dir != null; i++) {
            Path candidate = dir.resolve(".env");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * 解析 {@code .env} 文本。
     *
     * <p>只支持最朴素的 {@code KEY=VALUE}：
     * <ul>
     *   <li>忽略空行与 {@code #} 开头的注释行；</li>
     *   <li>值两端成对的单/双引号会被剥掉（{@code .env} 作者加引号是为了
     *       保住值里的空格，不该把引号当成值的一部分）；</li>
     *   <li><b>不做</b> {@code $VAR} 展开 —— 展开会引入求值顺序问题，
     *       而且 {@code .env} 里出现 {@code $} 的场景基本只有密钥本身。</li>
     * </ul>
     *
     * <p>行内注释（{@code KEY=value # 说明}）**不剥离**：密钥里出现 {@code #}
     * 完全可能，误剥离会把密钥截断成一把错误的钥匙 —— 这种错误在 401 之外
     * 没有任何提示，排查代价极高。
     */
    static Map<String, Object> parse(List<String> lines) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // 允许 `export KEY=VALUE` 写法（用户常从 shell 脚本里复制过来）
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (key.isEmpty()) {
                continue;
            }
            values.put(key, unquote(value));
        }
        return values;
    }

    /** 剥掉成对的单/双引号；不成对时原样返回（宁可留个引号，也不要猜） */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
