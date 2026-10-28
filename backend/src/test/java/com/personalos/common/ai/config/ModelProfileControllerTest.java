package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personalos.common.ai.config.dto.ModelListResult;
import com.personalos.common.ai.config.dto.ModelProfileDto;
import com.personalos.common.ai.config.dto.ModelProfileListDto;
import com.personalos.common.ai.config.dto.ProbeModelsRequest;
import com.personalos.common.ai.config.dto.ReorderModelProfilesRequest;
import com.personalos.common.ai.config.dto.SaveModelProfileRequest;
import com.personalos.common.api.ApiResponse;
import com.personalos.common.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 多配置接口的 Web 层测试（不启动容器）。
 *
 * <h2>必须钉住的两条契约</h2>
 * <ol>
 *   <li><b>响应永不含明文密钥。</b>这是接口级安全边界，任何新增端点都必须遵守。
 *       断言方式是把明文喂进服务层，再看 DTO 里剩下的是掩码；</li>
 *   <li><b>只有生效中的配置被改动才重切模型。</b>改一套备用配置却触发
 *       运行期模型重建，会让正在进行的对话突然换模型 —— 而用户只是编辑了
 *       一张没在用的卡片。</li>
 * </ol>
 */
class ModelProfileControllerTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    private ModelProfileService profileService;
    private ModelConfigService configService;
    private ModelConnectionTestService connectionTestService;
    private ModelListProbeService modelListProbeService;
    private ModelProfileController controller;

    @BeforeEach
    void setUp() {
        profileService = mock(ModelProfileService.class);
        configService = mock(ModelConfigService.class);
        connectionTestService = mock(ModelConnectionTestService.class);
        modelListProbeService = mock(ModelListProbeService.class);
        controller = new ModelProfileController(
                profileService, configService, connectionTestService, modelListProbeService);

        // 默认：任何实体都解析成一份带明文密钥的生效配置，
        // 以便验证 toDto() 真的做了掩码。
        when(configService.resolveFrom(any())).thenReturn(resolved("sk-secret-value-8f3a"));
    }

    // ------------------------------------------------------------------ 读取

    @Test
    @DisplayName("list(): 返回全部配置与唯一的生效 id")
    void listReturnsActiveId() {
        AiModelProfileEntity active = profile("生效中", true, 0);
        AiModelProfileEntity spare = profile("备用", false, 1);
        when(profileService.list()).thenReturn(List.of(active, spare));
        when(profileService.require(any())).thenReturn(active);

        ApiResponse<ModelProfileListDto> response = controller.list();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.data().profiles()).hasSize(2);
        assertThat(response.data().activeId()).isEqualTo(ID);
    }

    @Test
    @DisplayName("list(): 没有生效配置时 activeId 为 null")
    void listWithoutActiveHasNullId() {
        when(profileService.list()).thenReturn(List.of(profile("备用", false, 0)));

        assertThat(controller.list().data().activeId()).isNull();
    }

    /**
     * 安全边界的核心断言。
     *
     * <p>服务层返回的是明文（它要拿去构造 HTTP 客户端），控制器必须掩码。
     * 这条断言失败就意味着明文密钥进了 HTTP 响应体 —— 它会同时出现在浏览器内存、
     * 开发者工具网络面板与访问日志里。
     */
    @Test
    @DisplayName("响应中的密钥只有末 4 位掩码，绝无明文")
    void responseNeverContainsPlaintextKey() {
        when(profileService.list()).thenReturn(List.of(profile("配置", true, 0)));

        ModelProfileDto dto = controller.list().data().profiles().getFirst();

        assertThat(dto.apiKeyMasked()).isEqualTo("****8f3a");
        assertThat(dto.apiKeyMasked()).doesNotContain("secret");
        assertThat(dto.hasApiKey()).isTrue();
    }

    @Test
    @DisplayName("没有密钥时掩码为空串且 hasApiKey 为 false")
    void blankKeyRendersEmptyMask() {
        when(configService.resolveFrom(any())).thenReturn(resolved(""));
        when(profileService.list()).thenReturn(List.of(profile("配置", true, 0)));

        ModelProfileDto dto = controller.list().data().profiles().getFirst();

        assertThat(dto.apiKeyMasked()).isEmpty();
        assertThat(dto.hasApiKey()).isFalse();
    }

    // ------------------------------------------------------------------ 新建

    @Test
    @DisplayName("create(): 落库后返回掩码视图，且不触发模型切换")
    void createDoesNotSwitchModel() {
        when(profileService.create(any())).thenReturn(profile("新配置", false, 2));

        ApiResponse<ModelProfileDto> response = controller.create(
                new SaveModelProfileRequest("新配置", "dashscope", "https://api.example.com",
                        null, "sk-new", "qwen3.8-flash", 0.7, null));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.data().name()).isEqualTo("新配置");
        // 新建的配置不生效，因此不该碰运行期模型
        verify(profileService, never()).applyCurrent();
    }

    @Test
    @DisplayName("create(): 端点不以 http(s) 开头时拒绝")
    void createRejectsBadBaseUrl() {
        assertThatThrownBy(() -> controller.create(
                new SaveModelProfileRequest("配置", null, "ftp://x.example.com", null, null, "m", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("http");
    }

    @Test
    @DisplayName("create(): 温度越界时拒绝")
    void createRejectsOutOfRangeTemperature() {
        assertThatThrownBy(() -> controller.create(
                new SaveModelProfileRequest("配置", null, null, null, null, "m", 2.5, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("温度");
    }

    @Test
    @DisplayName("create(): 模型名超长时拒绝")
    void createRejectsTooLongModelName() {
        String tooLong = "m".repeat(121);
        assertThatThrownBy(() -> controller.create(
                new SaveModelProfileRequest("配置", null, null, null, null, tooLong, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过");
    }

    @Test
    @DisplayName("create(): 请求体为 null 时给出可读提示")
    void createRejectsNullBody() {
        assertThatThrownBy(() -> controller.create(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请求体");
    }

    // ------------------------------------------------------------------ 更新

    @Test
    @DisplayName("update(): 改的是生效配置时重切模型")
    void updateActiveProfileReappliesModel() {
        when(profileService.update(eq(ID), any())).thenReturn(profile("生效中", true, 0));
        when(profileService.require(ID)).thenReturn(profile("生效中", true, 0));

        controller.update(ID, new SaveModelProfileRequest(
                "生效中", null, null, null, null, "m", null, null));

        verify(profileService).applyCurrent();
    }

    @Test
    @DisplayName("update(): 改的是备用配置时不重切模型")
    void updateInactiveProfileDoesNotReapply() {
        when(profileService.update(eq(ID), any())).thenReturn(profile("备用", false, 1));
        when(profileService.require(ID)).thenReturn(profile("备用", false, 1));

        controller.update(ID, new SaveModelProfileRequest(
                "备用", null, null, null, null, "m", null, null));

        verify(profileService, never()).applyCurrent();
    }

    // ------------------------------------------------------------------ 探测模型列表

    @Test
    @DisplayName("probeModels(): 用表单里的端点与密钥直接探测")
    void probeModelsUsesFormValues() {
        when(modelListProbeService.probe(any(), any(), any(), any()))
                .thenReturn(new ModelListResult(true, List.of("gpt-4o", "gpt-4o-mini"), 120, null));

        ApiResponse<ModelListResult> response = controller.probeModels(
                new ProbeModelsRequest("OPENAI", "https://api.example.com/v1", "sk-form-key", null));

        assertThat(response.data().ok()).isTrue();
        assertThat(response.data().models()).containsExactly("gpt-4o", "gpt-4o-mini");
        // 新建配置时没有 profileId，必须用表单里的值，不能去查库
        verify(modelListProbeService).probe(
                eq(ModelProtocol.OPENAI), eq("https://api.example.com/v1"), eq("sk-form-key"), eq(List.of()));
        verify(profileService, never()).require(any());
    }

    @Test
    @DisplayName("probeModels(): 密钥留空但给了 profileId 时用库里那套的密钥")
    void probeModelsFallsBackToStoredKey() {
        when(profileService.require(ID)).thenReturn(profile("已有配置", false, 0));
        when(configService.resolveFrom(any())).thenReturn(resolved("sk-stored-key"));
        when(modelListProbeService.probe(any(), any(), any(), any()))
                .thenReturn(new ModelListResult(true, List.of("qwen-max"), 80, null));

        controller.probeModels(new ProbeModelsRequest(null, "https://api.example.com", "  ", ID));

        // 编辑态密钥框按约定永不回填 → 必须能用已保存的密钥探测
        verify(modelListProbeService).probe(
                eq(ModelProtocol.OPENAI), eq("https://api.example.com"), eq("sk-stored-key"), eq(List.of()));
    }

    @Test
    @DisplayName("probeModels(): 端点与密钥都留空时一并回落到库里那套")
    void probeModelsFallsBackToStoredEndpointToo() {
        when(profileService.require(ID)).thenReturn(profile("已有配置", false, 0));
        when(configService.resolveFrom(any())).thenReturn(resolved("sk-stored-key"));
        when(modelListProbeService.probe(any(), any(), any(), any()))
                .thenReturn(new ModelListResult(true, List.of("m"), 10, null));

        controller.probeModels(new ProbeModelsRequest("OLLAMA", null, null, ID));

        verify(modelListProbeService).probe(
                eq(ModelProtocol.OLLAMA), eq("https://api.example.com"), eq("sk-stored-key"), eq(List.of()));
    }

    @Test
    @DisplayName("probeModels(): 没有 profileId 且密钥为空时不查库，直接交给探测服务报错")
    void probeModelsWithoutProfileIdDoesNotQueryDatabase() {
        // 关键：不能拿环境变量里的密钥去探一个不相干的端点
        when(modelListProbeService.probe(any(), any(), any(), any()))
                .thenReturn(ModelListResult.failed("请先填写 API Key"));

        ApiResponse<ModelListResult> response = controller.probeModels(
                new ProbeModelsRequest("OPENAI", "https://api.example.com", null, null));

        assertThat(response.data().ok()).isFalse();
        verify(profileService, never()).require(any());
        verify(modelListProbeService).probe(
                eq(ModelProtocol.OPENAI), eq("https://api.example.com"), isNull(), eq(List.of()));
    }

    @Test
    @DisplayName("probeModels(): 协议参数原样传给探测服务")
    void probeModelsPassesProtocol() {
        when(modelListProbeService.probe(any(), any(), any(), any()))
                .thenReturn(new ModelListResult(true, List.of("llama3:8b"), 5, null));

        controller.probeModels(new ProbeModelsRequest("ollama", "http://localhost:11434", null, null));

        verify(modelListProbeService).probe(eq(ModelProtocol.OLLAMA), any(), any(), eq(List.of()));
    }

    @Test
    @DisplayName("probeModels(): 请求体里的模型清单透传给探测服务（决定优先补查谁）")
    void probeModelsPassesPreferredModels() {
        when(modelListProbeService.probe(any(), any(), any(), any()))
                .thenReturn(new ModelListResult(true, List.of("qwen3.8-flash"), 5, null));

        controller.probeModels(new ProbeModelsRequest(
                "OPENAI",
                "https://api.example.com",
                "sk",
                null,
                // 带空白与空串：必须被清理后再传，否则会拿 " " 去发请求
                List.of("qwen3.8-flash", "  ", "qwen-max")));

        /*
         * 这条断言守的是 V26 的核心修复：探测服务需要知道用户在用哪几个模型。
         *
         * 不透传的话后端只能按字母序取前 12 个补查详情，而 DashScope 的
         * qwen3.8-flash 排第 148 位 —— 用户最常用的模型窗口恒为空。
         * 空白项在 Controller 层就被 preferredModels() 过滤掉。
         */
        verify(modelListProbeService).probe(
                eq(ModelProtocol.OPENAI),
                eq("https://api.example.com"),
                eq("sk"),
                eq(List.of("qwen3.8-flash", "qwen-max")));
    }

    @Test
    @DisplayName("probeModels(): 请求体为 null 时给出可读提示")
    void probeModelsRejectsNullBody() {
        assertThatThrownBy(() -> controller.probeModels(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请求体");
    }

    @Test
    @DisplayName("probeModels(): 端点超长时拒绝")
    void probeModelsRejectsTooLongBaseUrl() {
        assertThatThrownBy(() -> controller.probeModels(
                new ProbeModelsRequest("OPENAI", "https://x.example.com/" + "a".repeat(600), "sk", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过");
    }

    @Test
    @DisplayName("probeModels(): 探测失败仍返回 200，由 ok=false 表达（不是接口错误）")
    void probeModelsReturnsOkFalseOnFailure() {
        when(modelListProbeService.probe(any(), any(), any(), any()))
                .thenReturn(ModelListResult.failed("接口不存在（404）"));

        ApiResponse<ModelListResult> response = controller.probeModels(
                new ProbeModelsRequest("OPENAI", "https://api.example.com", "sk", null));

        // 大量端点没有 /models，但它们能正常聊天 —— 这不是错误
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.data().ok()).isFalse();
        assertThat(response.data().models()).isEmpty();
        assertThat(response.data().error()).contains("404");
    }

    // ------------------------------------------------------------------ 启用

    @Test
    @DisplayName("activate(): 先切数据库状态，再在事务外应用模型")
    void activateAppliesModel() {
        when(profileService.require(ID)).thenReturn(profile("目标", true, 0));

        ApiResponse<ModelProfileDto> response = controller.activate(ID);

        assertThat(response.data().active()).isTrue();
        verify(profileService).activate(ID);
        verify(profileService).applyCurrent();
    }

    // ------------------------------------------------------------------ 删除

    @Test
    @DisplayName("delete(): 返回 deleted=true 的应答体")
    void deleteAcknowledges() {
        ApiResponse<com.personalos.task.dto.DeletionAck> response = controller.delete(ID);

        assertThat(response.data().deleted()).isTrue();
        verify(profileService).delete(ID);
    }

    // ------------------------------------------------------------------ 排序

    @Test
    @DisplayName("reorder(): 转发 id 顺序并回读列表")
    void reorderDelegatesAndReturnsList() {
        when(profileService.list()).thenReturn(List.of(profile("A", true, 0)));

        ApiResponse<ModelProfileListDto> response = controller.reorder(
                new ReorderModelProfilesRequest(List.of(OTHER_ID, ID)));

        verify(profileService).reorder(List.of(OTHER_ID, ID));
        assertThat(response.data().profiles()).hasSize(1);
    }

    @Test
    @DisplayName("reorder(): 请求体为 null 时不抛异常")
    void reorderNullBodyIsTolerated() {
        when(profileService.list()).thenReturn(List.of());

        ApiResponse<ModelProfileListDto> response = controller.reorder(null);

        verify(profileService).reorder(null);
        assertThat(response.data().profiles()).isEmpty();
    }

    // ------------------------------------------------------------------ 替身

    private static AiModelProfileEntity profile(String name, boolean active, int sortOrder) {
        AiModelProfileEntity entity = new AiModelProfileEntity(
                name, null, sortOrder, Instant.parse("2026-10-28T06:00:00Z"));
        if (active) {
            entity.activate(Instant.parse("2026-10-28T06:00:00Z"));
        }
        forceId(entity, ID);
        return entity;
    }

    /**
     * 给实体注入 id。
     *
     * <p>{@code @GeneratedValue} 由 JPA 在持久化时填充，这里没有容器，
     * 不注入的话 {@code activeId()} 永远是 null，断言会静默变成「通过」。
     */
    private static void forceId(AiModelProfileEntity entity, UUID id) {
        try {
            var field = AiModelProfileEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法为测试实体注入 id", ex);
        }
    }

    private static ModelConfigService.ResolvedConfig resolved(String apiKey) {
        return new ModelConfigService.ResolvedConfig(
                "https://api.example.com",
                apiKey,
                "qwen3.8-flash",
                0.7,
                "qwen3.7-text-embedding-flash",
                3,
                Instant.parse("2026-10-28T06:00:00Z"),
                Map.of("baseUrl", ModelConfigService.ConfigSource.DATABASE));
    }
}
