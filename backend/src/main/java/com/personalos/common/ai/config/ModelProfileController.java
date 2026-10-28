package com.personalos.common.ai.config;

import com.personalos.common.ai.config.dto.ModelListResult;
import com.personalos.common.ai.config.dto.ModelProfileDto;
import com.personalos.common.ai.config.dto.ModelProfileListDto;
import com.personalos.common.ai.config.dto.ProbeModelsRequest;
import com.personalos.common.ai.config.dto.ReorderModelProfilesRequest;
import com.personalos.common.ai.config.dto.SaveModelProfileRequest;
import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.task.dto.DeletionAck;
import java.util.List;
import java.util.Map;
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

    /** 字段长度上限，与 V21/V22 列宽一致 */
    private static final int BASE_URL_MAX = 500;
    private static final int API_KEY_MAX = 500;
    private static final int MODEL_NAME_MAX = 120;
    private static final int PRESET_ID_MAX = 60;
    private static final int PROTOCOL_MAX = 20;

    /** 模型清单条数上限，与 ModelListProbeService.MAX_MODELS 对齐（探测最多返回这么多） */
    private static final int MODEL_LIST_MAX = 500;

    private final ModelProfileService profileService;
    private final ModelConfigService configService;
    private final ModelConnectionTestService connectionTestService;
    private final ModelListProbeService modelListProbeService;

    public ModelProfileController(ModelProfileService profileService,
                                  ModelConfigService configService,
                                  ModelConnectionTestService connectionTestService,
                                  ModelListProbeService modelListProbeService) {
        this.profileService = profileService;
        this.configService = configService;
        this.connectionTestService = connectionTestService;
        this.modelListProbeService = modelListProbeService;
    }

    // ------------------------------------------------------------------ 读取

    /** 全部配置 + 当前生效者 */
    @GetMapping
    public ApiResponse<ModelProfileListDto> list() {
        List<AiModelProfileEntity> profiles = profileService.list();
        // 一次批量取回全部清单，避免逐张卡片查一次（N+1）
        Map<UUID, List<AiModelProfileModelEntity>> models = profileService.modelsOf(profiles);
        List<ModelProfileDto> dtos = profiles.stream()
                .map(entity -> toDto(entity, models.getOrDefault(entity.getId(), List.of())))
                .toList();
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

    /**
     * 探测某个端点支持哪些模型（cc-switch 的「获取模型列表」）。
     *
     * <h2>为什么收的是「草稿」而不是已保存配置的 id</h2>
     * 用户通常是在**填表的过程中**点这个按钮 —— 先填端点与密钥，看看
     * 这个端点有什么模型，再从里面挑一个填进去。若必须先把配置保存下来
     * 才能探测，「新建一套」就变成「先瞎填一个模型名保存 → 再回来探测 →
     * 再改」，而瞎填的模型名正是我们要帮他避免的。
     *
     * <h2>密钥留空时怎么处理</h2>
     * 传了 {@code profileId} 就用那套配置已保存的密钥来探测。这样编辑一套
     * 已有配置时（密钥框按约定永不回填）也能直接点探测，不必重新输一遍 key。
     * 没传 {@code profileId} 且密钥为空 → 明确报「请先填写 API Key」，
     * 而不是拿环境变量里的密钥去探一个不相干的端点。
     *
     * <p>返回 200 且 {@code ok=false} 表示「探测没成功」，不是接口错误 ——
     * 大量端点根本不提供模型列表，但它们能正常聊天。
     */
    @PostMapping("/probe-models")
    public ApiResponse<ModelListResult> probeModels(@RequestBody ProbeModelsRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }
        checkLength(request.baseUrl(), BASE_URL_MAX, "端点地址");
        ModelProtocol protocol = ModelProtocol.parse(request.protocol());
        String baseUrl = request.baseUrl();
        String apiKey = request.apiKey();

        // 表单没填密钥但指明了配置 → 用库里那套的密钥
        if ((apiKey == null || apiKey.isBlank()) && request.profileId() != null) {
            AiModelProfileEntity existing = profileService.require(request.profileId());
            ModelConfigService.ResolvedConfig resolved = configService.resolveFrom(existing);
            apiKey = resolved.apiKey();
            // 端点也留空时同样回落到那套配置的生效值，否则无从探测
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = resolved.baseUrl();
            }
        }
        return ApiResponse.ok(modelListProbeService.probe(protocol, baseUrl, apiKey));
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
        return toDto(entity, profileService.modelsOf(entity.getId()));
    }

    /** 已取到清单时的映射（列表接口用，避免重复查） */
    private ModelProfileDto toDto(AiModelProfileEntity entity,
                                  List<AiModelProfileModelEntity> models) {
        ModelConfigService.ResolvedConfig resolved = resolveFor(entity);
        return new ModelProfileDto(
                entity.getId(),
                entity.getName(),
                entity.getPresetId(),
                resolved.baseUrl(),
                entity.getProtocol(),
                ModelConfigService.mask(resolved.apiKey()),
                resolved.hasApiKey(),
                resolved.chatModel(),
                resolved.temperature(),
                resolved.embeddingModel(),
                namesOf(models, AiModelProfileModelEntity.Kind.CHAT),
                namesOf(models, AiModelProfileModelEntity.Kind.EMBEDDING),
                namesOf(models, AiModelProfileModelEntity.Kind.AUDIO),
                // 用生效值而非 entity.getAudioModel()：前者带「清单第一项」语义，
                // 后者是写入时的快照，两者在「清单为空但单值列还有残留」时会不一致
                resolved.audioModel() == null ? "" : resolved.audioModel(),
                entity.isActive(),
                entity.getSortOrder(),
                entity.getRevision(),
                entity.getUpdatedAt(),
                resolved.sources());
    }

    /** 从清单里挑出某一类的模型名，保持库里的顺序 */
    private static List<String> namesOf(List<AiModelProfileModelEntity> models,
                                        AiModelProfileModelEntity.Kind kind) {
        if (models == null || models.isEmpty()) {
            return List.of();
        }
        return models.stream()
                .filter(model -> model.kindEnum() == kind)
                .map(AiModelProfileModelEntity::getModelName)
                .toList();
    }

    private static ModelProfileService.SaveProfileCommand toCommand(SaveModelProfileRequest request) {
        return new ModelProfileService.SaveProfileCommand(
                request.name(),
                request.presetId(),
                request.baseUrl(),
                request.protocol(),
                request.apiKey(),
                request.chatModel(),
                request.temperature(),
                request.embeddingModel(),
                request.chatModels(),
                request.embeddingModels(),
                request.audioModels());
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
        checkLength(request.protocol(), PROTOCOL_MAX, "协议类型");
        checkModelList(request.chatModels(), MODEL_NAME_MAX, "对话模型");
        checkModelList(request.embeddingModels(), MODEL_NAME_MAX, "嵌入模型");
        checkModelList(request.audioModels(), MODEL_NAME_MAX, "语音模型");

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

    /**
     * 校验模型清单。
     *
     * <p>只限制**条数与单项长度**，不校验模型名是否真实存在：端点会动态增减模型，
     * 而且用户完全可能先填一个还没开通的模型名 —— 那不是数据错误，
     * 顶多是一次失败的调用，由探测结果去告诉他。
     *
     * <p>上限 500 与 {@code ModelListProbeService.MAX_MODELS} 对齐：
     * 探测最多返回 500 个，用户「全选」也不会超过它。
     */
    private static void checkModelList(List<String> models, int max, String fieldName) {
        if (models == null) {
            return;
        }
        if (models.size() > MODEL_LIST_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    fieldName + "最多保存 " + MODEL_LIST_MAX + " 个");
        }
        for (String model : models) {
            checkLength(model, max, fieldName + "名");
        }
    }

    private static void checkLength(String value, int max, String fieldName) {
        if (value != null && value.length() > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    fieldName + "长度不能超过 " + max + " 个字符");
        }
    }
}
