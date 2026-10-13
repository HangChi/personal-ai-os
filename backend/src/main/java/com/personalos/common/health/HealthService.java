package com.personalos.common.health;

import com.personalos.common.config.AppProperties;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 健康状态服务。
 *
 * <p>阶段 0 的语义边界：本服务只表达「应用进程存活 + 版本可读」。
 * 数据库连通性由框架自带的 {@code /actuator/health}（含 {@code db} 组件）表达，
 * 二者职责不重叠。详见 {@code contracts/http-api.md}。
 */
@Service
public class HealthService {

    private final AppProperties appProperties;

    /**
     * 构造器注入（宪法原则 III：禁止字段注入）。
     *
     * @param appProperties 应用元信息配置
     */
    public HealthService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * @return 当前应用健康状态
     */
    public HealthStatusDto currentStatus() {
        return new HealthStatusDto("UP", appProperties.name(), appProperties.version(), Instant.now());
    }
}
