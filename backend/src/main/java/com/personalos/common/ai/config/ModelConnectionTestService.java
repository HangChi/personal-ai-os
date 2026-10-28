package com.personalos.common.ai.config;

import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.ai.config.dto.ModelConnectionTestResult;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 模型连通性测试。
 *
 * <h2>为什么真的发一次请求，而不是「检查字段填了没有」</h2>
 * 字段齐全不代表能用：密钥可能是错的、端点可能是通的但模型名不存在、
 * 余额可能已用尽。这三类问题在配置页上看起来完全一样（都填好了），
 * 只有真的发一次请求才能区分。测试按钮的价值全在这里。
 *
 * <h2>为什么用虚拟线程 + FutureTask 卡超时</h2>
 * 与 {@code ChatSummaryService} 同一手法：Spring AI 的 {@code .call()} 没有
 * 单次调用超时，HTTP 客户端超时是全局的、不能为一个测试单独收紧。
 * 不卡超时的话，点拨「测试」遇到不可达端点要等到 HTTP 超时才返回，
 * 界面看起来像卡死。虚拟线程很便宜，让它挂着自然结束即可。
 */
@Service
public class ModelConnectionTestService {

    private static final Logger log = LoggerFactory.getLogger(ModelConnectionTestService.class);

    /**
     * 测试超时。
     *
     * <p>取 20 秒：跨境访问 DashScope / OpenAI 正常在 1~3 秒，
     * 留足余量给首字节慢的情况；又不能太长 —— 用户是盯着按钮等结果的。
     */
    private static final long TIMEOUT_SECONDS = 20;

    /**
     * 测试用的固定短问。
     *
     * <p>要求极短回答，是为了让这次调用尽可能便宜且快 —— 它要验证的是管道通畅，
     * 不是模型能力。带一句「只回复两个字」能把思考型模型的输出压到最低。
     */
    private static final String TEST_PROMPT = "请只回复两个字：正常";

    /** 回显截断长度：够看出「模型确实答了话」，又不会撑破界面 */
    private static final int PREVIEW_MAX_CHARS = 60;

    private final AiChatClientProvider aiProvider;

    public ModelConnectionTestService(AiChatClientProvider aiProvider) {
        this.aiProvider = aiProvider;
    }

    /**
     * 用**当前生效**的模型发一次最小请求。
     *
     * <h3>为什么测的是「当前生效」而不是「表单里的值」</h3>
     * 前端在保存之前可以先把表单值存下来再测，但那样测的是「另一套还没生效的配置」，
     * 与用户看到的「当前模型」不是一回事，容易得出反直觉的结论
     * （测试成功、保存后聊天失败，因为保存的是另一份）。
     * 这里固定测当前生效的那套：想验新配置就先保存，语义单一。
     *
     * @return 测试结果（失败也返回 200，由 {@code ok} 字段表达 —— 这是查询而非错误）
     */
    public ModelConnectionTestResult test() {
        long startNanos = System.nanoTime();

        if (!aiProvider.isConfigured()) {
            return new ModelConnectionTestResult(false, 0, null, null,
                    "当前没有可用的聊天模型，请先保存模型配置或检查 AI_CHAT_PROVIDER");
        }
        String modelName = modelName();

        FutureTask<String> task = new FutureTask<>(() -> aiProvider.requirePlainChatClient()
                .prompt()
                .user(TEST_PROMPT)
                .call()
                .content());
        Thread.ofVirtual().name("model-conn-test").start(task);

        try {
            String reply = task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            return new ModelConnectionTestResult(true, latencyMs, modelName, preview(reply), null);
        } catch (TimeoutException ex) {
            task.cancel(true);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.warn("模型连通性测试超时（{}秒）", TIMEOUT_SECONDS);
            return new ModelConnectionTestResult(false, latencyMs, modelName, null,
                    "请求超时（超过 " + TIMEOUT_SECONDS + " 秒），请检查端点地址与网络");
        } catch (ExecutionException ex) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            String reason = describe(ex.getCause() == null ? ex : ex.getCause());
            log.warn("模型连通性测试失败：{}", reason);
            return new ModelConnectionTestResult(false, latencyMs, modelName, null, reason);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ModelConnectionTestResult(false, 0, modelName, null, "测试被中断");
        }
    }

    /**
     * 当前模型的名称。
     *
     * <p>从选项里取而不是读配置：配置里写的是「希望用哪个」，
     * 选项里才是「实际会请求哪个」。两者不一致时（例如换模型失败），
     * 这里如实反映后者。
     */
    private String modelName() {
        var model = aiProvider.currentModel();
        if (model == null) {
            return null;
        }
        var options = model.getOptions();
        if (options instanceof org.springframework.ai.openai.OpenAiChatOptions openAiOptions) {
            return openAiOptions.getModel();
        }
        return options == null ? model.getClass().getSimpleName() : options.getClass().getSimpleName();
    }

    private static String preview(String reply) {
        if (reply == null) {
            return "";
        }
        String trimmed = reply.strip();
        return trimmed.length() <= PREVIEW_MAX_CHARS
                ? trimmed
                : trimmed.substring(0, PREVIEW_MAX_CHARS) + "…";
    }

    /**
     * 把异常转成面向用户的一句话。
     *
     * <p>原始异常常常是一长串 URL + JSON，直接给用户看没有帮助。
     * 这里保留状态码这类可操作的信息，其余压成一句。
     */
    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        // 401 / 403 / 404 / 429 是最常见的四类，直接点明含义
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("401") || lower.contains("unauthorized")) {
            return "认证失败（401）：API Key 无效或已过期";
        }
        if (lower.contains("403") || lower.contains("forbidden")) {
            return "无权限（403）：该密钥不能访问此模型";
        }
        if (lower.contains("404") || lower.contains("not found")) {
            return "未找到（404）：端点地址或模型名可能写错";
        }
        if (lower.contains("429") || lower.contains("rate limit")) {
            return "被限流（429）：请求过于频繁或额度已用尽";
        }
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }
}
