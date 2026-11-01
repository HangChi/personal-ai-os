package com.personalos.export;

import com.personalos.common.config.AppProperties;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把各模块的 {@link ExportContributor} 快照组装成一个扁平 JSON 结构。
 *
 * <p><b>扁平而非嵌套</b>：顶层直接是 {@code tasks} / {@code calendarEvents} 等数组，
 * 而不是 {@code {"sections": {"tasks": [...]}}}。用户拿到文件后多半是
 * {@code jq '.tasks | length'} 这种用法，多一层嵌套只会让每个查询都更啰嗦。
 *
 * <p><b>counts 与数组同源</b>：{@code counts.X} 由该数组的**实际长度**算出，
 * 不另跑一次 {@code count(*)}。两次统计之间存在并发写入窗口，
 * 而「文件说 8 条、实际给了 9 条」足以让人对整份备份失去信任。
 */
@Service
public class DataExportService {

    /** 导出格式版本。字段增删时递增，便于将来写导入或迁移脚本时判别 */
    public static final String FORMAT_VERSION = "1";

    private final AppProperties appProperties;
    private final List<ExportContributor> contributors;
    private final Clock clock;
    private final tools.jackson.databind.ObjectMapper mapper;

    public DataExportService(AppProperties appProperties,
                             List<ExportContributor> contributors,
                             Clock clock, tools.jackson.databind.ObjectMapper mapper) {
        this.mapper = mapper;
        this.appProperties = appProperties;
        this.contributors = contributors;
        this.clock = clock;
    }

    /** 事务在异步响应线程内打开；所有分批查询共享同一个 PostgreSQL 快照。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public void writeTo(java.io.OutputStream output) {
        try (var json = mapper.createGenerator(output)) {
            json.writeStartObject();
            json.writeStringProperty("application", appProperties.name());
            json.writeStringProperty("version", appProperties.version());
            json.writeStringProperty("formatVersion", FORMAT_VERSION);
            json.writePOJOProperty("exportedAt", clock.instant());
            Map<String, Long> counts = new LinkedHashMap<>();
            for (var contributor : contributors.stream().sorted(Comparator.comparing(ExportContributor::key)).toList()) {
                json.writeName(contributor.key());
                counts.put(contributor.key(), contributor.writeTo(json));
            }
            json.writePOJOProperty("counts", counts);
            json.writeEndObject();
        }
    }
}
