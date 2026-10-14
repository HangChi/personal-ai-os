package com.personalos.common.health;

import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.config.AppProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 健康状态服务。
 *
 * <p><b>为什么补依赖探测</b>（技术债清偿）：阶段 0 刻意让本接口只报「进程存活」，
 * 数据库状态交给 {@code /actuator/health}。实际使用中首页只展示本接口——
 * 数据库挂了用户看到的是一片绿。依赖状态必须出现在用户真正会看的地方。
 *
 * <p><b>关键依赖 vs 可选依赖</b>：数据库是关键依赖（它挂了，任务/知识库/日程全不可用），
 * 整体状态随之 DOWN；模型是可选依赖（没配模型，聊天返回 50300，但应用整体健康），
 * 只进 {@code components} 做信息展示，不拖垮整体状态。
 *
 * <p><b>探测必须廉价</b>：{@code SELECT 1} 不碰业务表；失败只记日志不抛出——
 * 健康接口自身的故障不该把请求变成 500。
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final AppProperties appProperties;
    private final JdbcTemplate jdbcTemplate;
    private final AiChatClientProvider aiChatClientProvider;

    /**
     * 构造器注入（宪法原则 III：禁止字段注入）。
     *
     * @param appProperties        应用元信息配置
     * @param jdbcTemplate         数据库连通性探测（{@code SELECT 1}）
     * @param aiChatClientProvider 模型配置状态探测（不发起任何模型调用）
     */
    public HealthService(AppProperties appProperties,
                         JdbcTemplate jdbcTemplate,
                         AiChatClientProvider aiChatClientProvider) {
        this.appProperties = appProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.aiChatClientProvider = aiChatClientProvider;
    }

    /**
     * @return 当前应用健康状态（含依赖分量）
     */
    public HealthStatusDto currentStatus() {
        boolean databaseUp = probeDatabase();
        Map<String, String> components = new LinkedHashMap<>();
        components.put("database", databaseUp ? "UP" : "DOWN");
        components.put("ai", aiChatClientProvider.isConfigured() ? "CONFIGURED" : "NOT_CONFIGURED");
        return new HealthStatusDto(databaseUp ? "UP" : "DOWN",
                appProperties.name(), appProperties.version(), Instant.now(), components);
    }

    private boolean probeDatabase() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (DataAccessException ex) {
            log.warn("健康检查：数据库连接失败：{}", ex.getMessage());
            return false;
        }
    }
}
