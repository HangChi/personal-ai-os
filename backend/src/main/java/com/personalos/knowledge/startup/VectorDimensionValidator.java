package com.personalos.knowledge.startup;

import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.service.VectorStoreInspector;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动期维度一致性校验（FR-013）。
 *
 * <h2>为什么必须在启动时校验</h2>
 * 维度在**三个地方**各写了一份：Flyway 迁移的 {@code vector(1024)}、应用配置里的
 * {@code app.knowledge.embedding-dimensions}、以及嵌入模型的真实输出维度。
 * 三者不一致时，pgvector 只会在**写入的那一刻**抛出类似
 * {@code expected 1024 dimensions, not 768} 的原始 SQL 错误 ——
 * 那时用户已经完成了上传，而且报错信息里看不出「该改配置还是该改迁移」。
 *
 * <p>与其让错误发生得又晚又难懂，不如在启动时就把「表 ↔ 配置」这一对拦住。
 *
 * <h2>为什么这里不校验「模型 ↔ 配置」</h2>
 * {@code EmbeddingModel.dimensions()} 的默认实现会**真的执行一次嵌入调用**来探测维度。
 * Ollama 未启动时这会抛异常，从而破坏阶段 0 起就承诺的「无模型也能启动」（FR-016）。
 * 因此「模型 ↔ 配置」的校验推迟到实际写入时（见 {@code KnowledgeDocumentService} 的异常翻译）。
 */
@Component
public class VectorDimensionValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorDimensionValidator.class);

    private static final Pattern DIMENSION_PATTERN = Pattern.compile("\\(\\s*(\\d+)\\s*\\)");

    private final VectorStoreInspector inspector;
    private final KnowledgeProperties properties;

    public VectorDimensionValidator(VectorStoreInspector inspector, KnowledgeProperties properties) {
        this.inspector = inspector;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        int expected = properties.embeddingDimensions();
        String location = inspector.schemaName() + "." + inspector.tableName() + ".embedding";

        String columnType = inspector.embeddingColumnType().orElseThrow(() -> new IllegalStateException(
                "找不到向量表的 embedding 列（" + location + "）。"
                        + "请确认 Flyway 迁移已执行、且 spring.ai.vectorstore.pgvector.table-name/schema-name 配置正确。"));

        Optional<Integer> actual = parseDimension(columnType);
        if (actual.isEmpty()) {
            throw new IllegalStateException("无法从列类型 \"" + columnType + "\" 解析出向量维度（" + location + "）。"
                    + "请确认 pgvector 扩展已安装。");
        }
        if (actual.get() != expected) {
            // 错误信息里必须出现「维度」二字：这是 SC-007 的断言口径，也是排错者的第一关键词
            throw new IllegalStateException(String.format(
                    "向量维度不一致：表结构为 %s（%d 维），而配置 app.knowledge.embedding-dimensions = %d。"
                            + "两条修复路径：(1) 把配置改成 %d 并确保嵌入模型输出该维度；"
                            + "(2) 新增一个迁移，重建 %s 列为 vector(%d) 并重建索引。",
                    columnType, actual.get(), expected, actual.get(), location, expected));
        }

        log.info("向量维度校验通过：{} = {}，与配置一致（{} 维）", location, columnType, expected);
    }

    private Optional<Integer> parseDimension(String columnType) {
        Matcher matcher = DIMENSION_PATTERN.matcher(columnType);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }
}
