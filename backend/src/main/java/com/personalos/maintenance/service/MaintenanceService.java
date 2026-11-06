package com.personalos.maintenance.service;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.config.RuntimeRole;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 不具备导入、文件系统、Docker、模型或外部HTTP能力。 */
@Service
public class MaintenanceService {
    public static final int MAX_BYTES = 20 << 20;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JsonCodec json;
    private final RuntimeRole role;
    private final boolean heartbeatEnabled;
    private final UUID instanceId = UUID.randomUUID();
    private final Semaphore validators = new Semaphore(2);

    public MaintenanceService(JdbcTemplate jdbc, PlatformTransactionManager manager, JsonCodec json,
                              RuntimeRole role, @Value("${app.maintenance.heartbeat-enabled:true}") boolean heartbeatEnabled) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(15);
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(15);
        this.json = json;
        this.role = role;
        this.heartbeatEnabled = heartbeatEnabled;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decode(String value) { return json.fromJson(value, Map.class); }

    public Map<String, Object> snapshot() {
        return decode(jdbc.queryForObject("SELECT maintenance_snapshot()::text", String.class));
    }

    /** 持续续写本实例；不把启动记录/协调租约伪装成心跳。 */
    @Scheduled(fixedDelay = 30000, initialDelay = 1000)
    public void heartbeat() {
        if (!heartbeatEnabled) return;
        jdbc.update("INSERT INTO runtime_heartbeat(instance_id,backend,role) VALUES(?,'JAVA',?) "
                + "ON CONFLICT(instance_id) DO UPDATE SET last_seen_at=clock_timestamp()", instanceId, role.name());
        jdbc.update("DELETE FROM runtime_heartbeat WHERE last_seen_at<now()-interval '7 days'");
    }

    public Map<String, Object> verify(byte[] raw) { return check(raw, false); }
    public Map<String, Object> drill(byte[] raw) { return check(raw, true); }

    private Map<String, Object> check(byte[] raw, boolean external) {
        if (raw.length > (external ? 16384 : MAX_BYTES)) throw invalid("文件超过校验大小上限");
        if (!validators.tryAcquire()) throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "正在处理其他校验，请稍后重试");
        try {
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
                if (text.indexOf('\0') >= 0) throw new CharacterCodingException();
            } catch (CharacterCodingException ex) {
                if (external) throw invalid("报告需为有效UTF-8 JSON");
                text = "";
            }
            String content = text;
            return transaction.execute(status -> {
                jdbc.execute("SET LOCAL statement_timeout='10s'");
                String result = jdbc.queryForObject(external ? "SELECT maintenance_drill_text(?)::text" : "SELECT maintenance_verify_text(?)::text", String.class, content);
                if (external && !"UNVERIFIED".equals(decode(result).get("status"))) throw invalid("外部报告需填写工具、摘要、完成时间及PASSED/FAILED结果");
                return decode(jdbc.queryForObject("SELECT maintenance_save(?,?,?::jsonb)::text", String.class,
                        external ? "EXTERNAL_DRILL" : "BUSINESS_JSON", digest(raw), result));
            });
        } finally { validators.release(); }
    }

    private String digest(byte[] raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256不可用", ex); }
    }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED, message); }
}
