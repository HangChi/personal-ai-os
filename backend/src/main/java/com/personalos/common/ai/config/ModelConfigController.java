package com.personalos.common.ai.config;

import com.personalos.common.ai.config.dto.ModelConfigDto;
import com.personalos.common.ai.config.dto.ModelConnectionTestResult;
import com.personalos.common.ai.config.dto.SaveModelConfigRequest;
import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型 API 配置（设置页）：读取 / 保存（热生效）/ 连通性测试。
 *
 * <p>路径挂在 {@code /api/v1/settings/model} 而不是 {@code /api/v1/ai/config}：
 * 这是**设置页**的数据，将来还会有其它设置项（界面偏好已在前端本地，
 * 但服务端偏好如「是否允许自动记忆」也属于同一族），归到一个命名空间下更整齐。
 *
 * <h2>安全约定（本控制器的唯一硬约束）</h2>
 * <b>任何响应都不得包含明文 API Key。</b>读取时用
 * {@link ModelConfigService#mask(String)} 处理；保存与测试的响应同样不含明文。
 * 这条约束写在这里而不是注释在 DTO 上，因为它是一个**接口级**的约定 ——
 * 新增端点的人必须遵守。
 */
@RestController
@RequestMapping("/api/v1/settings/model")
public class ModelConfigController {

    /** 温度下限/上限，与 OpenAI 协议一致 */
    private static final double TEMPERATURE_MIN = 0.0;
    private static final double TEMPERATURE_MAX = 2.0;

    /** 字段长度上限，与 V20 的列宽一致 */
    private static final int BASE_URL_MAX = 500;
    private static final int API_KEY_MAX = 500;
    private static final int MODEL_NAME_MAX = 120;

    private final ModelConfigService modelConfigService;
    private final ModelConnectionTestService connectionTestService;

    public ModelConfigController(ModelConfigService modelConfigService,
                                 ModelConnectionTestService connectionTestService) {
        this.modelConfigService = modelConfigService;
        this.connectionTestService = connectionTestService;
    }

    /**
     * 当前生效的模型配置（密钥已掩码）。
     */
    @GetMapping
    public ApiResponse<ModelConfigDto> get() {
        return ApiResponse.ok(toDto(modelConfigService.resolve()));
    }

    /**
     * 保存配置并立即让它生效。
     *
     * <h3>为什么保存与生效是两次调用，而不是全塞进 service 的一个事务里</h3>
     * 这是本轮踩到的一个真实 bug：原先 {@code save()} 标了 {@code @Transactional}
     * 并在方法末尾调用 {@code applyToRuntime()}，而后者在模型构造失败时抛
     * {@link BusinessException} —— 异常穿出事务边界会让**整个事务回滚**，
     * 于是「配置已保存，但按该配置初始化模型失败」这句提示在说谎：
     * 配置根本没保存，数据库里一行都没有。
     *
     * <p>正确的语义是「保存成功」与「切换成功」两件事分开报告：
     * 配置落库（事务内，几乎不会失败）→ 事务提交 → 再切模型（事务外，可能失败）。
     * 切模型失败时配置保留，用户可以改个参数重试，不必重新填一遍。
     */
    @PutMapping
    public ApiResponse<ModelConfigDto> save(@RequestBody SaveModelConfigRequest request) {
        validate(request);
        // 第一步：落库并提交。此调用返回即意味着事务已提交。
        ModelConfigService.ResolvedConfig saved = modelConfigService.save(
                new ModelConfigService.SaveCommand(
                        request.baseUrl(),
                        request.apiKey(),
                        request.chatModel(),
                        request.temperature(),
                        request.embeddingModel()));
        // 第二步：事务外切模型。失败会抛 BusinessException，但配置已经存下了。
        modelConfigService.applyToRuntime(saved);
        return ApiResponse.ok(toDto(saved));
    }

    /**
     * 用当前生效的模型发一次最小请求，验证真的能用。
     *
     * <p>用 POST 而非 GET：它会产生一次真实的模型调用（有副作用、可能计费），
     * 不该被浏览器预取或缓存。
     */
    @PostMapping("/test")
    public ApiResponse<ModelConnectionTestResult> test() {
        return ApiResponse.ok(connectionTestService.test());
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 表单校验。
     *
     * <p>按项目约定不用 Bean Validation 注解，在这里显式检查并抛出
     * {@link ErrorCode#VALIDATION_FAILED}。检查项只有三类：
     * 长度不超列宽、温度在协议范围内、端点地址是个像样的 URL。
     */
    private static void validate(SaveModelConfigRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }
        checkLength(request.baseUrl(), BASE_URL_MAX, "端点地址");
        checkLength(request.apiKey(), API_KEY_MAX, "API Key");
        checkLength(request.chatModel(), MODEL_NAME_MAX, "对话模型名");
        checkLength(request.embeddingModel(), MODEL_NAME_MAX, "嵌入模型名");

        Double temperature = request.temperature();
        if (temperature != null && (temperature < TEMPERATURE_MIN || temperature > TEMPERATURE_MAX)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "温度必须在 " + TEMPERATURE_MIN + " 到 " + TEMPERATURE_MAX + " 之间");
        }

        String baseUrl = request.baseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            String trimmed = baseUrl.strip();
            // 只接受 http/https：写 file:// 或 javascript: 没有正当用途，
            // 而这段值会被当作 HTTP 客户端的目标地址。
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "端点地址必须以 http:// 或 https:// 开头");
            }
        }
    }

    private static void checkLength(String value, int max, String fieldName) {
        if (value != null && value.length() > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    fieldName + "长度不能超过 " + max + " 个字符");
        }
    }

    private static ModelConfigDto toDto(ModelConfigService.ResolvedConfig config) {
        return new ModelConfigDto(
                config.baseUrl(),
                ModelConfigService.mask(config.apiKey()),
                config.hasApiKey(),
                config.chatModel(),
                config.temperature(),
                config.embeddingModel(),
                config.revision(),
                config.updatedAt(),
                config.sources(),
                true,
                // 嵌入模型不能热切换：向量表维度由 DDL 固定为 1024，
                // 换一个不同维度的模型会让写入直接失败。如实告知，不假装支持。
                false);
    }
}
