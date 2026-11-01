package com.personalos.common.maintenance;

import com.personalos.chat.repository.TokenUsageRepository;
import com.personalos.common.idempotency.OperationRecordRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 用量与审计记录默认永久保留；只有显式设置天数才会批量清理。 */
@Service
public class DataRetentionService {
    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);
    private final TokenUsageRepository usage;
    private final OperationRecordRepository operations;
    private final Clock clock;
    private final int usageDays;
    private final int operationDays;

    public DataRetentionService(TokenUsageRepository usage, OperationRecordRepository operations, Clock clock,
            @Value("${app.retention.token-usage-days:0}") int usageDays,
            @Value("${app.retention.operation-record-days:0}") int operationDays) {
        if (usageDays < 0 || operationDays < 0) {
            throw new IllegalArgumentException("数据保留天数不能为负数（0 表示永久保留）");
        }
        this.usage = usage;
        this.operations = operations;
        this.clock = clock;
        this.usageDays = usageDays;
        this.operationDays = operationDays;
    }

    @Scheduled(cron = "${app.retention.cron:0 30 4 * * *}")
    public void cleanup() {
        var now = clock.instant();
        if (usageDays > 0) {
            int count = usage.deleteByCreatedAtBefore(now.minus(usageDays, ChronoUnit.DAYS));
            log.info("用量保留策略清理 {} 条记录", count);
        }
        if (operationDays > 0) {
            int count = operations.deleteByCreatedAtBefore(now.minus(operationDays, ChronoUnit.DAYS));
            log.info("操作记录保留策略清理 {} 条记录", count);
        }
    }
}
