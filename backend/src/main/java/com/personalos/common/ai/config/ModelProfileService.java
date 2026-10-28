package com.personalos.common.ai.config;

import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 大模型「多套配置」的读写与切换。
 *
 * <h2>为什么另起一个服务而不改造 ModelConfigService</h2>
 * {@code ModelConfigService} 的职责是「把一套配置解析成生效值」——
 * 三层回落、密钥三态、运行期应用。它是**单个配置**的语义，被大量测试直接 new。
 * 多配置管理是另一层：增删改查、切换、排序。两者混在一起会让那个类
 * 既管数据又管列表，测试也会互相牵连。
 *
 * <p>因此分层是：
 * <ul>
 *   <li>本类 —— 多套配置的 CRUD 与「哪一套生效」；</li>
 *   <li>{@link ModelConfigService} —— 把某一套（或没有套）解析成生效值并应用；</li>
 *   <li>{@link ModelConfigController} —— 对外接口，负责掩码与校验。</li>
 * </ul>
 *
 * <h2>三条必须守住的不变量</h2>
 * <ol>
 *   <li><b>至多一行为 active。</b>数据库用部分唯一索引强制，应用层再用
 *       「先全部取消并 flush、再启用目标」保证语句顺序不会撞索引
 *       （见 {@link #activate}）。</li>
 *   <li><b>至少保留一套配置，且必须有一套生效。</b>删掉最后一行的后果是
 *       「聊天永久不可用」而没有入口恢复；删掉当前生效行则会让运行期模型
 *       与数据库状态脱节。两者都在 {@link #delete} 里拦下。</li>
 *   <li><b>保存与切模型跨事务边界。</b>与 {@link ModelConfigService#save} 同因：
 *       事务内抛异常会回滚，「配置已保存」就变成谎话。本类的事务只碰数据库，
 *       切换运行期模型一律由控制器在事务外调 {@link ModelConfigService#apply}。</li>
 * </ol>
 */
@Service
public class ModelProfileService {

    private static final Logger log = LoggerFactory.getLogger(ModelProfileService.class);

    /** 名称长度上限，与 V21 的 {@code VARCHAR(60)} 一致 */
    static final int NAME_MAX = 60;

    private final AiModelProfileRepository repository;
    private final AiModelConfigRepository legacyRepository;
    private final ModelConfigService configService;
    private final AiChatClientProvider provider;
    private final Clock clock;

    public ModelProfileService(AiModelProfileRepository repository,
                               AiModelConfigRepository legacyRepository,
                               ModelConfigService configService,
                               AiChatClientProvider provider,
                               Clock clock) {
        this.repository = repository;
        this.legacyRepository = legacyRepository;
        this.configService = configService;
        this.provider = provider;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 全部配置，按展示顺序。
     *
     * <p>密钥在返回前**已掩码**（由控制器做，这里保持明文以便内部应用）。
     */
    @Transactional(readOnly = true)
    public List<AiModelProfileEntity> list() {
        return repository.findAllByOrderBySortOrderAscCreatedAtAsc();
    }

    /**
     * 取一套配置；不存在时抛 404。
     *
     * @param id 配置 id
     */
    @Transactional(readOnly = true)
    public AiModelProfileEntity require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("模型配置不存在：" + id));
    }

    /** 当前生效的那一套；没有时为 empty（此时回落到环境变量） */
    @Transactional(readOnly = true)
    public java.util.Optional<AiModelProfileEntity> current() {
        return repository.findFirstByActiveTrue();
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 新建一套配置。
     *
     * <p>新配置**默认不生效**（{@code active = false}）：用户从预设库里加一套
     * 备份配置，不该把当前正在用的那套直接换掉。想切换要显式点「启用」。
     *
     * @param command 表单
     * @return 新建的实体
     */
    @Transactional
    public AiModelProfileEntity create(SaveProfileCommand command) {
        Instant now = clock.instant();
        AiModelProfileEntity entity = new AiModelProfileEntity(
                requireName(command.name()),
                blankToNull(command.presetId()),
                nextSortOrder(),
                now);
        entity.overwrite(
                entity.getName(),
                blankToNull(command.presetId()),
                blankToNull(command.baseUrl()),
                blankToNull(command.apiKey()),
                blankToNull(command.chatModel()),
                command.temperature(),
                blankToNull(command.embeddingModel()),
                command.protocol(),
                now);
        AiModelProfileEntity saved = repository.save(entity);
        log.info("已新建模型配置：id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    /**
     * 更新一套配置（整体覆盖）。
     *
     * <p>密钥三态与 V20 完全一致：{@code null} = 不改动，空串 = 清空回落，
     * 非空 = 覆盖。这是「用户没碰密钥框」与「用户想清空」的唯一区分方式。
     *
     * @param id      配置 id
     * @param command 表单
     */
    @Transactional
    public AiModelProfileEntity update(UUID id, SaveProfileCommand command) {
        AiModelProfileEntity entity = require(id);
        String apiKey = command.apiKey() == null
                ? entity.getApiKey()
                : blankToNull(command.apiKey());
        entity.overwrite(
                requireName(command.name()),
                blankToNull(command.presetId()),
                blankToNull(command.baseUrl()),
                apiKey,
                blankToNull(command.chatModel()),
                command.temperature(),
                blankToNull(command.embeddingModel()),
                command.protocol(),
                clock.instant());
        AiModelProfileEntity saved = repository.save(entity);
        log.info("已更新模型配置：id={}, name={}, revision={}",
                saved.getId(), saved.getName(), saved.getRevision());
        return saved;
    }

    /**
     * 改名。
     *
     * @param id   配置 id
     * @param name 新名称
     */
    @Transactional
    public AiModelProfileEntity rename(UUID id, String name) {
        AiModelProfileEntity entity = require(id);
        entity.rename(requireName(name), clock.instant());
        return repository.save(entity);
    }

    /**
     * 启用某一套配置。**只改数据库状态**，不切换运行期模型。
     *
     * <h2>为什么必须先取消全部再启用</h2>
     * 部分唯一索引 {@code uq_ai_model_profile_single_active} 只允许一行
     * {@code active = TRUE}。若直接把这行置 TRUE，此时旧行仍是 TRUE ——
     * 库层立刻拒绝。若只依赖 Hibernate 在 flush 时自行排序，语句顺序
     * 没有保证（实测批量操作按主键顺序发出，很容易把 TRUE 排在 FALSE 前面）。
     *
     * <p>因此顺序是硬编码的：
     * <ol>
     *   <li>把其他所有行置 FALSE 并 {@code saveAndFlush} —— 这一步之后库里
     *       一个 TRUE 都没有，任何顺序都不会撞索引；</li>
     *   <li>目标行置 TRUE 并 {@code saveAndFlush}。</li>
     * </ol>
     * 中间那一瞬间「没有生效配置」是可接受的：它只存在于事务内部，
     * 事务外的读者看不到（Read Committed）。
     *
     * <p>切换运行期模型由调用方在事务外做 —— 见
     * {@link ModelConfigController#activate}。
     *
     * @param id 目标配置 id
     * @return 启用后的实体
     */
    @Transactional
    public AiModelProfileEntity activate(UUID id) {
        AiModelProfileEntity target = require(id);
        if (target.isActive()) {
            // 重复点同一张卡不该报错，也不该刷新 updatedAt —— 用户没改任何东西
            log.debug("配置 id={} 已是生效状态，跳过", id);
            return target;
        }
        Instant now = clock.instant();
        for (AiModelProfileEntity other : repository.findAllByOrderBySortOrderAscCreatedAtAsc()) {
            if (other.isActive()) {
                other.deactivate(now);
                // 必须立刻落库：这条 FALSE 必须先于后面的 TRUE 到达数据库
                repository.saveAndFlush(other);
            }
        }
        target.activate(now);
        AiModelProfileEntity saved = repository.saveAndFlush(target);
        log.info("已切换生效配置：id={}, name={}, chatModel={}",
                saved.getId(), saved.getName(), saved.getChatModel());
        return saved;
    }

    /**
     * 删除一套配置。
     *
     * <h2>两条拒绝规则（都是「防止把应用弄成不可用」）</h2>
     * <ol>
     *   <li><b>不能删当前生效行。</b>删掉之后库里有配置但没一套生效，运行期
     *       模型却还在跑它 —— 数据库与现实脱节，用户在界面上看到的「没有任何
     *       配置生效」与「聊天还能用」互相矛盾。要求先切到另一套。</li>
     *   <li><b>不能删最后一行。</b>删光之后设置页没有任何配置可选，
     *       聊天直接不可用，而恢复路径是「重新手填一遍」。
     *       这条其实被上一条蕴含（如果只剩一行且它是生效行），但分开写更清楚：
     *       将来若允许「没有生效配置」的状态，第一条会放宽、第二条仍要保留。</li>
     * </ol>
     *
     * @param id 配置 id
     */
    @Transactional
    public void delete(UUID id) {
        AiModelProfileEntity entity = require(id);
        if (entity.isActive()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "不能删除当前生效的配置，请先切换到其他配置");
        }
        if (repository.count() <= 1) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "至少需要保留一套配置，无法删除最后一套");
        }
        repository.delete(entity);
        log.info("已删除模型配置：id={}, name={}", id, entity.getName());
    }

    /**
     * 调整排序。
     *
     * <p>按传入的 id 顺序重排 sortOrder（0, 1, 2…）。不推进 revision、
     * 不改 updatedAt —— 纯展示顺序，不影响任何配置内容。
     *
     * @param orderedIds 期望顺序的 id 列表；缺失的配置保持原有 sortOrder
     */
    @Transactional
    public void reorder(List<UUID> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            return;
        }
        List<AiModelProfileEntity> all = repository.findAllByOrderBySortOrderAscCreatedAtAsc();
        Map<UUID, AiModelProfileEntity> byId = new LinkedHashMap<>();
        all.forEach(entity -> byId.put(entity.getId(), entity));

        List<AiModelProfileEntity> changed = new ArrayList<>();
        int index = 0;
        for (UUID id : orderedIds) {
            AiModelProfileEntity entity = byId.remove(id);
            if (entity != null) {
                entity.reorder(index++);
                changed.add(entity);
            }
        }
        // 未出现在入参里的配置排到后面，而不是保持原 sortOrder 造成交叉
        for (AiModelProfileEntity leftover : byId.values()) {
            leftover.reorder(index++);
            changed.add(leftover);
        }
        repository.saveAll(changed);
    }

    // ------------------------------------------------------------------ 运行时应用

    /**
     * 把「当前生效配置」应用到运行期模型。
     *
     * <p><b>必须在事务外调用。</b>模型构造失败会抛
     * {@link BusinessException}，在事务内抛会让事务回滚，连带把刚提交的
     * 配置变更一起撤销 —— 「已切换」的提示就成了假话。
     *
     * <p>没有生效配置、或该配置没有密钥时，退回环境变量那套（若可用）。
     */
    public void applyCurrent() {
        configService.applyResolved(resolveCurrent());
    }

    /**
     * 解析当前生效配置（已合并环境变量与内置默认）。
     *
     * <p>没有生效行时返回「纯环境变量」的解析结果 —— 与 V20 时代行为一致。
     */
    @Transactional(readOnly = true)
    public ModelConfigService.ResolvedConfig resolveCurrent() {
        AiModelProfileEntity active = repository.findFirstByActiveTrue().orElse(null);
        if (active != null) {
            return configService.resolveFrom(active);
        }
        // 迁移前的老数据（V20 单行表）仍可作为兜底：万一 V21 的搬迁因故没跑，
        // 用户的配置不该凭空消失。这条路径只在异常场景生效。
        AiModelConfigEntity legacy = legacyRepository.findSingleton().orElse(null);
        return configService.resolveFromLegacy(legacy);
    }

    // ------------------------------------------------------------------ 工具

    private int nextSortOrder() {
        Integer max = repository.maxSortOrder();
        return max == null ? 0 : max + 1;
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "配置名称不能为空");
        }
        if (trimmed.length() > NAME_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "配置名称长度不能超过 " + NAME_MAX + " 个字符");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 新建 / 更新配置的表单。
     *
     * @param name           展示名（必填）
     * @param presetId       预设标识；null 表示自定义
     * @param baseUrl        端点地址；空表示回落到环境变量
     * @param apiKey         密钥；**null 表示不改动**，空串表示清空（仅 update 有意义）
     * @param chatModel      对话模型名
     * @param temperature    温度；null 表示沿用服务端默认
     * @param embeddingModel 嵌入模型名（改动需重启）
     */
    public record SaveProfileCommand(String name,
                                     String presetId,
                                     String baseUrl,
                                     String protocol,
                                     String apiKey,
                                     String chatModel,
                                     Double temperature,
                                     String embeddingModel) {
    }
}
