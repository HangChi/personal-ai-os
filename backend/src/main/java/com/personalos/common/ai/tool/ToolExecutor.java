package com.personalos.common.ai.tool;

import com.personalos.common.ai.ToolContextKeys;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.idempotency.IdempotencyOutcome;
import com.personalos.common.idempotency.IdempotencyRequest;
import com.personalos.common.idempotency.IdempotencyService;
import com.personalos.common.idempotency.IdempotentResult;
import com.personalos.common.idempotency.OperationSource;
import com.personalos.common.json.JsonCodec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * 工具执行的公共外壳：幂等 + 审计 + 异常归一。
 *
 * <p>抽出来的原因是这三件事对所有工具都一样，而写进每个工具方法会让业务逻辑被淹没。
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final IdempotencyService idempotencyService;
    private final JsonCodec jsonCodec;

    public ToolExecutor(IdempotencyService idempotencyService, JsonCodec jsonCodec) {
        this.idempotencyService = idempotencyService;
        this.jsonCodec = jsonCodec;
    }

    /**
     * 执行一个**写**工具：带工具层幂等。
     *
     * <p>幂等键包含轮次标识，因此只有「同一轮内的同工具同参数」才会被判为重复。
     */
    public String write(String toolName, Map<String, Object> arguments, ToolContext toolContext,
                        Supplier<ToolExecutionResult> action) {
        String conversationId = contextValue(toolContext, ToolContextKeys.CONVERSATION_ID);
        String turnId = contextValue(toolContext, ToolContextKeys.TURN_ID);
        String digest = jsonCodec.digestOf(arguments);
        String key = IdempotencyService.toolKey(conversationId, turnId, toolName, digest);
        return execute(toolName, arguments, toolContext, key, action);
    }

    /**
     * 执行一个**读**工具：只审计，不做幂等。
     *
     * <p>读操作刻意不去重：模型在同一轮里「创建任务 → 再查一次列表」是常见且合理的动作，
     * 若把第二次查询当成重复请求回放旧结果，模型就会看到过期数据。
     * 用随机键代替派生键，即可只保留审计而跳过去重。
     */
    public String read(String toolName, Map<String, Object> arguments, ToolContext toolContext,
                       Supplier<ToolExecutionResult> action) {
        return execute(toolName, arguments, toolContext, UUID.randomUUID().toString(), action);
    }

    private String execute(String toolName, Map<String, Object> arguments, ToolContext toolContext,
                           String key, Supplier<ToolExecutionResult> action) {
        String conversationId = contextValue(toolContext, ToolContextKeys.CONVERSATION_ID);
        String turnId = contextValue(toolContext, ToolContextKeys.TURN_ID);
        String digest = jsonCodec.digestOf(arguments);

        IdempotencyRequest request = new IdempotencyRequest(key, OperationSource.TOOL, toolName,
                digest, conversationId, turnId);

        try {
            IdempotentResult result = idempotencyService.execute(request, () -> {
                ToolExecutionResult execution;
                try {
                    execution = action.get();
                } catch (BusinessException ex) {
                    // 业务校验失败：作为 REJECTED 返回给模型，而不是抛异常打断工具循环。
                    // 这样审计记录里的状态也是 REJECTED（说明"没执行"），而不是 FAILED。
                    execution = ToolExecutionResult.rejected(ex.getMessage());
                } catch (RuntimeException ex) {
                    log.error("工具执行异常 tool={}", toolName, ex);
                    execution = ToolExecutionResult.error("工具执行失败，请稍后重试");
                }
                return IdempotentResult.of(jsonCodec.toJson(execution), toOutcome(execution));
            });

            if (result.replayed()) {
                ToolExecutionResult original =
                        jsonCodec.fromJson(result.responsePayload(), ToolExecutionResult.class);
                return jsonCodec.toJson(ToolExecutionResult.duplicate(
                        "该操作在本轮已执行过，未重复执行。首次结果：" + original.message(),
                        original.data()));
            }
            return result.responsePayload();
        } catch (BusinessException ex) {
            // 例如同一幂等键对应了不同参数
            return jsonCodec.toJson(ToolExecutionResult.rejected(ex.getMessage()));
        } catch (RuntimeException ex) {
            log.error("工具外壳执行失败 tool={}", toolName, ex);
            return jsonCodec.toJson(ToolExecutionResult.error("工具执行失败，请稍后重试"));
        }
    }

    /**
     * 构造**字段顺序稳定**的参数映射。
     *
     * <p>幂等摘要是对 JSON 取哈希，而 {@code Map.of(...)} 的迭代顺序不保证稳定 ——
     * 用它会导致同样的参数算出不同摘要，把「重复请求」误判成「冲突」。
     */
    public static Map<String, Object> arguments(Consumer<Map<String, Object>> filler) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        filler.accept(arguments);
        return arguments;
    }

    /**
     * 从 {@link ToolContext} 中取一个上下文值。
     *
     * <p>对外公开：工具实现可能只需要上下文而不需要幂等外壳（例如读取会话标识用于日志）。
     */
    public static String contextValue(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(key);
        return value == null ? null : value.toString();
    }

    private IdempotencyOutcome toOutcome(ToolExecutionResult execution) {
        return switch (execution.status()) {
            case "OK" -> IdempotencyOutcome.SUCCESS;
            case "REQUIRES_CONFIRMATION" -> IdempotencyOutcome.REQUIRES_CONFIRMATION;
            case "DUPLICATE" -> IdempotencyOutcome.DUPLICATE;
            case "REJECTED" -> IdempotencyOutcome.REJECTED;
            default -> IdempotencyOutcome.FAILED;
        };
    }

    /**
     * 解析 UUID 参数，失败时给出可读原因（而非 {@code IllegalArgumentException}）。
     */
    public static UUID parseUuid(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, fieldName + " 不能为空");
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    fieldName + " 不是合法的任务标识：" + raw);
        }
    }
}
