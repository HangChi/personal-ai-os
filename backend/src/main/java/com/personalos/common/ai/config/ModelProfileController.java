package com.personalos.common.ai.config;

import com.personalos.common.ai.config.dto.ModelProfileDto;
import com.personalos.common.ai.config.dto.ModelProfileListDto;
import com.personalos.common.ai.config.dto.ReorderModelProfilesRequest;
import com.personalos.common.ai.config.dto.SaveModelProfileRequest;
import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.task.dto.DeletionAck;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型配置的**多套管理**：列表 / 新建 / 更新 / 启用 / 删除 / 排序。
 *
 * <p>与 {@link ModelConfigController} 的分工：
 * <ul>
 *   <li>那个是「当前生效配置」的单套读写（{@code /settings/model}），
 *       保留给兼容与表单直改；</li>
 *   <li>本类是「多套配置」的管理入口（{@code /settings/model/profiles}），
 *       参照 cc-switch 的 provider 列表：多张卡片、一键启用。</li>
 * </ul>
 *
 * <h2>本控制器唯一的硬约束</h2>
 * <b>任何响应都不得包含明文 API Key。</b>所有出参都过
 * {@link ModelConfigService#mask(String)}。新增端点的人必须遵守 ——
 * 这是接口级约定，不是某个 DTO 的属性。
 *
 * <h2>为什么「落库」与「切换模型」在每个写端点里都分成两步</h2>
 * 服务层的事务只碰数据库。切换运行期模型（{@link ModelConfigService#applyResolved}）
 * 会真的构造 HTTP 客户端，可能因端点/密钥/模型名有问题而抛异常；
 * 若它在事务内抛，整个事务回滚 —— 用户看到「保存失败」，而实际情况是
 * 「配置其实存下来了，只是连不上」。两件事的失败概率与修复方式完全不同，
 * 必须分开报告。
 *
 * <p>因此顺序固定为：<b>先在事务内落库并提交 → 再在事务外应用</b>。
 * 服务层方法返回值即代表事务已提交（Spring 的代理在方法返回后提交）。
 */
@RestController
@RequestMapping("/api/v1/settings/model/profiles")
public class ModelProfileController {

    /** 温度范围，与 OpenAI 协议一致 */
    private static final double TEMPERATURE_MIN = 0.0;
    private static final double TEMPERATURE_MAX = 2.0;

    /** 字段长度上限，与 V21 列宽一致 */
    private static final int BASE_URL_MAX = 500;
    private static final int API_KEY_MAX = 500;
    private static final int MODEL_NAME_MAX = 120;
    private static final int PRESET_ID_MAX = 60;

    private final ModelProfileService profileService;
    private final ModelConfigService configService;
    private final ModelConnectionTestService connectionTestService;

    public ModelProfileController(ModelProfileService profileService,
                                  ModelConfigService configService,
                                  ModelConnectionTestService connectionTestService) {
        this.profileService = profileService;
        this.configService = configService;
        this.connectionTestService = connectionTestService;
    }

    // ------------------------------------------------------------------ 读取

    /** 全部配置 + 当前生效者 */
    @GetMapping
    public ApiResponse<ModelProfileListDto> list() {
        List<AiModelProfileEntity> profiles = profileService.list();
        List<ModelProfileDto> dtos = profiles.stream().map(this::toDto).toList();
        UUID activeId = profiles.stream()
                .filter(AiModelProfileEntity::isActive)
                .map(AiModelProfileEntity::getId)
                .findFirst()
                .orElse(null);
        return ApiResponse.ok(new ModelProfileListDto(dtos, activeId));
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 新建一套配置。
     *
     * <p>新建后**不自动启用**：从预设库加一套备份配置不该把正在用的那套换掉。
     * 想切换要显式调 {@link #activate}。
     */
    @PostMapping
    public ApiResponse<ModelProfileDto> create(@RequestBody SaveModelProfileRequest request) {
        validate(request);
        AiModelProfileEntity created = profileService.create(toCommand(request));
        return ApiResponse.ok(toDto(created));
    }

    /**
     * 更新一套配置并（若它正生效）立即应用。
     *
     * <p>只有生效中的配置被改动才需要重切模型；改一套备用的配置不影响运行期。
     */
    @PutMapping("/{id}")
    public ApiResponse<ModelProfileDto> update(@PathVariable UUID id,
                                               @RequestBody SaveModelProfileRequest request) {
        validate(request);
        // 第一步：落库并提交
        AiModelProfileEntity updated = profileService.update(id, toCommand(request));
        // 第二步：仅当它是生效配置时，事务外重切模型
        if (updated.isActive()) {
            profileService.applyCurrent();
        }
        return ApiResponse.ok(toDto(profileService.require(id)));
    }

    /**
     * 启用某一套配置并立即生效。
     *
     * <p>这是整页最核心的动作（cc-switch 的「Enable」）。
     */
    @PostMapping("/{id}/activate")
    public ApiResponse<ModelProfileDto> activate(@PathVariable UUID id) {
        // 第一步：事务内只改「哪一行 active」
        profileService.activate(id);
        // 第二步：事务外把新生效配置装到运行期。失败会抛业务异常，
        // 但数据库里的切换结果已经提交 —— 用户可以改配置后重试启用。
        profileService.applyCurrent();
        return ApiResponse.ok(toDto(profileService.require(id)));
    }

    /**
     * 删除一套配置。
     *
     * <p>删除当前生效行会被服务层拒绝（见 {@link ModelProfileService#delete}）：
     * 那样的后果是「库里有配置但没一套生效」，而运行期模型还在跑它。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<DeletionAck> delete(@PathVariable UUID id) {
        profileService.delete(id);
        return ApiResponse.ok(new DeletionAck(true));
    }

    /**
     * 调整顺序。
     *
     * <p>不触发模型切换：顺序纯属展示，与哪一套生效无关。
     */
    @PutMapping("/order")
    public ApiResponse<ModelProfileListDto> reorder(@RequestBody ReorderModelProfilesRequest request) {
        profileService.reorder(request == null ? null : request.ids());
        return list();
    }

    /**
     * 用**当前生效**的配置发一次最小请求。
     *
     * <p>与 {@link ModelConfigController#test} 等价，放在这里是为了让卡片列表
     * 上的「测试」按钮指向同一语义：测的永远是已生效的那套，不是卡片里的草稿。
     */
    @PostMapping("/test")
    public ApiResponse<com.personalos.common.ai.config.dto.ModelConnectionTestResult> test() {
        return ApiResponse.ok(connectionTestService.test());
    }

    // ------------------------------------------------------------------ 内部

    private ModelConfigService.ResolvedConfig resolveFor(AiModelProfileEntity entity) {
        return configService.resolveFrom(entity);
    }

    /**
     * 实体 → 对外视图。
     *
     * <p>逐字段来源取自 {@link ModelConfigService#resolveFrom}：卡片上显示的是
     * **生效值**（可能是环境变量兜底来的），来源标记告诉用户这一点。
     */
    private ModelProfileDto toDto(AiModelProfileEntity entity) {
        ModelConfigService.ResolvedConfig resolved = resolveFor(entity);
        return new ModelProfileDto(
                entity.getId(),
                entity.getName(),
                entity.getPresetId(),
                resolved.baseUrl(),
                ModelConfigService.mask(resolved.apiKey()),
                resolved.hasApiKey(),
                resolved.chatModel(),
                resolved.temperature(),
                resolved.embeddingModel(),
                entity.isActive(),
                entity.getSortOrder(),
                entity.getRevision(),
                entity.getUpdatedAt(),
                resolved.sources());
    }

    private static ModelProfileService.SaveProfileCommand toCommand(SaveModelProfileRequest request) {
        return new ModelProfileService.SaveProfileCommand(
                request.name(),
                request.presetId(),
                request.baseUrl(),
                request.apiKey(),
                request.chatModel(),
                request.temperature(),
                request.embeddingModel());
    }

    /**
     * 表单校验。
     *
     * <p>按项目约定不用 Bean Validation 注解，在这里显式检查并抛
     * {@link ErrorCode#VALIDATION_FAILED}。名称的非空与长度在服务层校验
     * （那里也要用），这里只查会落到列宽与协议范围的项。
     */
    private static void validate(SaveModelProfileRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }
        checkLength(request.baseUrl(), BASE_URL_MAX, "端点地址");
        checkLength(request.apiKey(), API_KEY_MAX, "API Key");
        checkLength(request.chatModel(), MODEL_NAME_MAX, "对话模型名");
        checkLength(request.embeddingModel(), MODEL_NAME_MAX, "嵌入模型名");
        checkLength(request.presetId(), PRESET_ID_MAX, "预设标识");

        Double temperature = request.temperature();
        if (temperature != null && (temperature < TEMPERATURE_MIN || temperature > TEMPERATURE_MAX)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "温度必须在 " + TEMPERATURE_MIN + " 到 " + TEMPERATURE_MAX + " 之间");
        }

        String baseUrl = request.baseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            String trimmed = baseUrl.strip();
            // 只接受 http/https：这个值会被当作 HTTP 客户端的目标地址，
            // file:// 或 javascript: 没有正当用途。
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
}
