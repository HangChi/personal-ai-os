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

    public DataExportService(AppProperties appProperties,
                             List<ExportContributor> contributors,
                             Clock clock) {
        this.appProperties = appProperties;
        this.contributors = contributors;
        this.clock = clock;
    }

    /**
     * 生成导出内容。
     *
     * <p>只读事务保证单表内读一致性（个人量级下跨表严格一致没有实际意义，
     * 也不值得为它加全局锁）。
     *
     * @return 可直接序列化为 JSON 的有序 Map
     */
    @Transactional(readOnly = true)
    public Map<String, Object> export() {
        // 按 key 排序：不依赖 Spring 注入 List 的顺序，输出文件稳定可比对
        List<ExportContributor> ordered = contributors.stream()
                .sorted(Comparator.comparing(ExportContributor::key))
                .toList();

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Object> sections = new LinkedHashMap<>();
        for (ExportContributor contributor : ordered) {
            List<?> data = contributor.snapshot();
            sections.put(contributor.key(), data);
            counts.put(contributor.key(), data.size());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", appProperties.name());
        body.put("version", appProperties.version());
        body.put("formatVersion", FORMAT_VERSION);
        body.put("exportedAt", clock.instant());
        body.put("counts", counts);
        body.putAll(sections);
        return body;
    }
}
