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
    private final AiModelProfileModelRepository modelRepository;
    private final AiModelConfigRepository legacyRepository;
    private final ModelConfigService configService;
    private final AiChatClientProvider provider;
    private final Clock clock;

    public ModelProfileService(AiModelProfileRepository repository,
                               AiModelProfileModelRepository modelRepository,
                               AiModelConfigRepository legacyRepository,
                               ModelConfigService configService,
                               AiChatClientProvider provider,
                               Clock clock) {
        this.repository = repository;
        this.modelRepository = modelRepository;
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
                // 单值列 = 清单第一项（见 effectiveChatModel 的说明）
                effectiveChatModel(command),
                command.temperature(),
                effectiveEmbeddingModel(command),
                effectiveAudioModel(command),
                command.protocol(),
                now);
        AiModelProfileEntity saved = repository.save(entity);
        replaceModels(saved.getId(), command.chatModels(), command.embeddingModels(),
                command.audioModels());
        log.info("已新建模型配置：id={}, name={}, 对话模型数={}, 嵌入模型数={}, 语音模型数={}",
                saved.getId(), saved.getName(),
                countOf(command.chatModels()), countOf(command.embeddingModels()),
                countOf(command.audioModels()));
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
                effectiveChatModel(command),
                command.temperature(),
                effectiveEmbeddingModel(command),
                effectiveAudioModel(command),
                command.protocol(),
                clock.instant());
        AiModelProfileEntity saved = repository.save(entity);
        replaceModels(id, command.chatModels(), command.embeddingModels(), command.audioModels());
        log.info("已更新模型配置：id={}, name={}, revision={}, 对话模型数={}, 嵌入模型数={}, 语音模型数={}",
                saved.getId(), saved.getName(), saved.getRevision(),
                countOf(command.chatModels()), countOf(command.embeddingModels()),
                countOf(command.audioModels()));
        return saved;
    }

    /**
     * 该写进单值列的对话模型名：清单第一项优先，否则用命令里的 {@code chatModel}。
     *
     * <h2>为什么保留单值列并且让它等于清单第一项</h2>
     * 单值列 {@code chat_model} 是「这套配置默认用哪个模型」，被三处依赖：
     * 启动期的 {@link ModelConfigService#applyOnStartup}、启用配置时的
     * {@link ModelConfigService#applyResolved}、以及未指定模型的会话。
     * 若让它与清单各行其是，就会出现「卡片上写着 qwen-max，默认请求发的是 qwen-plus」。
     *
     * <p>把「清单第一项」定义为默认，让用户只需维护一份有序清单 ——
     * 想换默认模型就把它拖到第一位，不必再单独填一个字段。
     *
     * <p>清单为空时回落到命令里的 {@code chatModel}：那是老客户端（V22 及以前
     * 的前端）提交的字段，继续支持它们不会带来额外成本。
     */
    private static String effectiveChatModel(SaveProfileCommand command) {
        String fromList = firstOf(command.chatModels());
        return fromList != null ? fromList : blankToNull(command.chatModel());
    }

    /** 嵌入模型的默认值，规则同 {@link #effectiveChatModel} */
    private static String effectiveEmbeddingModel(SaveProfileCommand command) {
        String fromList = firstOf(command.embeddingModels());
        return fromList != null ? fromList : blankToNull(command.embeddingModel());
    }

    /**
     * 默认语音模型的取值（V24）。
     *
     * <p><b>没有单值列可以回落</b>：语音模型是 V24 才有的概念，
     * V23 及以前不存在 {@code audioModel} 单值字段。因此这里唯一来源就是清单
     * 第一项；清单为空即为 null（= 不配语音模型，转写回落到浏览器自带识别）。
     *
     * <p>注意与另外两个 {@code effectiveXxx} 的差异：它们有「老客户端传了单值列」
     * 这条兼容路径，这里**刻意没有** —— 语音模型从来没有过单值形态，
     * 给一个不存在的历史字段留兼容位只会让人以为它存在过。
     */
    private static String effectiveAudioModel(SaveProfileCommand command) {
        return firstOf(command.audioModels());
    }

    /** 列表里第一个非空白项；没有时返回 null */
    private static String firstOf(List<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static int countOf(List<String> values) {
        return values == null ? 0 : (int) values.stream()
                .filter(value -> value != null && !value.isBlank())
                .count();
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

    // ------------------------------------------------------------------ 模型清单（V23）

    /**
     * 某套配置保存的模型清单。
     *
     * <p>清单为空时**回落到单值列**：V23 之前保存的配置只有
     * {@code chat_model} 一个模型名，迁移已经把它搬进子表；但若在已被手工改过的
     * 库上迁移没跑到，这里仍要让界面看到一个模型而不是「还没有模型」。
     * 这条兜底与 {@link #resolveCurrent} 里回落 V20 老表是同一个思路：
     * 数据搬迁出问题时，用户不该看到自己从未做过的改动。
     *
     * @param profileId 配置 id
     * @return 该配置的模型清单；顺序为 kind 升序（CHAT 在前）、清单内顺序
     */
    @Transactional(readOnly = true)
    public List<AiModelProfileModelEntity> modelsOf(UUID profileId) {
        List<AiModelProfileModelEntity> models =
                modelRepository.findByProfileIdOrderByKindAscSortOrderAscIdAsc(profileId);
        if (!models.isEmpty()) {
            return models;
        }
        AiModelProfileEntity profile = repository.findById(profileId).orElse(null);
        return profile == null ? List.of() : fallbackModels(profile);
    }

    /**
     * 一次取回多套配置的模型清单，供列表接口使用。
     *
     * <p>逐套查一次是 N+1，见 {@link AiModelProfileModelRepository#findByProfileIdInOrderByKindAscSortOrderAscIdAsc}。
     * 返回的 Map 里没有键表示「这套配置没有任何模型」；键存在但值为空列表
     * 只可能来自兜底路径 —— 调用方按「有没有值」判断即可。
     *
     * @param profiles 全部配置
     * @return 配置 id → 模型清单
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<AiModelProfileModelEntity>> modelsOf(List<AiModelProfileEntity> profiles) {
        if (profiles.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = profiles.stream().map(AiModelProfileEntity::getId).toList();
        Map<UUID, List<AiModelProfileModelEntity>> grouped = new LinkedHashMap<>();
        for (AiModelProfileModelEntity model : modelRepository
                .findByProfileIdInOrderByKindAscSortOrderAscIdAsc(ids)) {
            grouped.computeIfAbsent(model.getProfileId(), key -> new ArrayList<>()).add(model);
        }
        // 兜底：子表里完全没有记录的配置，用单值列凑一条
        for (AiModelProfileEntity profile : profiles) {
            grouped.computeIfAbsent(profile.getId(), key -> fallbackModels(profile));
        }
        return grouped;
    }

    /**
     * 用子表里的清单覆盖某套配置的模型。
     *
     * <p><b>整体替换</b>而非增量合并：设置页提交的是整份清单，用户删掉一个模型
     * 就是要它消失。增量合并会让「删除」永远不生效 —— 删了的项因为「库里本来有」
     * 又被保留下来。
     *
     * <p>先删后插的顺序不可颠倒：反过来会在删除前撞上唯一约束
     * {@code uq_ai_model_profile_model_name}（用户把一个模型从清单中间挪到前面，
     * 语义上没变，但插入先发生就会与还没删掉的那条重复）。
     *
     * @param profileId 配置 id
     * @param chatModels     对话模型名清单（按用户排序，可含空串，会被丢弃）
     * @param embeddingModels 嵌入模型名清单
     * @param audioModels    语音模型名清单（V24）
     */
    @Transactional
    public void replaceModels(UUID profileId, List<String> chatModels, List<String> embeddingModels,
                              List<String> audioModels) {
        modelRepository.deleteByProfileId(profileId);
        // 必须立刻落库：紧接着的插入若是与旧行同名，会撞唯一约束
        modelRepository.flush();

        Instant now = clock.instant();
        List<AiModelProfileModelEntity> toSave = new ArrayList<>();
        appendModels(toSave, profileId, AiModelProfileModelEntity.Kind.CHAT, chatModels, now);
        appendModels(toSave, profileId, AiModelProfileModelEntity.Kind.EMBEDDING, embeddingModels, now);
        appendModels(toSave, profileId, AiModelProfileModelEntity.Kind.AUDIO, audioModels, now);
        if (!toSave.isEmpty()) {
            modelRepository.saveAll(toSave);
        }
    }

    /**
     * 只替换对话与嵌入清单，保留语音清单不变（V23 及以前的调用方）。
     *
     * <p>为什么不是「语音清单一并清空」：这个重载的存在意义就是「老代码不认识
     * 语音模型」。让它在保存时把用户配好的语音模型删掉，属于「升级后丢配置」。
     *
     * @param profileId       配置 id
     * @param chatModels      对话模型名清单
     * @param embeddingModels 嵌入模型名清单
     */
    @Transactional
    public void replaceModels(UUID profileId, List<String> chatModels, List<String> embeddingModels) {
        replaceModels(profileId, chatModels, embeddingModels,
                namesOf(profileId, AiModelProfileModelEntity.Kind.AUDIO));
    }

    /** 某套配置下某一类的模型名，保持库里的顺序 */
    private List<String> namesOf(UUID profileId, AiModelProfileModelEntity.Kind kind) {
        return modelRepository.findByProfileIdOrderByKindAscSortOrderAscIdAsc(profileId).stream()
                .filter(model -> model.kindEnum() == kind)
                .map(AiModelProfileModelEntity::getModelName)
                .toList();
    }

    /** 把一组模型名转成实体并追加到目标列表；跳过空串与重复项 */
    private static void appendModels(List<AiModelProfileModelEntity> target, UUID profileId,
                                     AiModelProfileModelEntity.Kind kind,
                                     List<String> modelNames, Instant now) {
        if (modelNames == null) {
            return;
        }
        // 去重在这里自己做：数据库的唯一约束会拒绝重复，但那是一个 500 级别的
        // 约束冲突，而「重复提交同一个模型名」是用户很容易无意做出的事。
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String raw : modelNames) {
            if (raw == null) {
                continue;
            }
            String name = raw.strip();
            if (name.isEmpty() || !seen.add(name)) {
                continue;
            }
            target.add(new AiModelProfileModelEntity(profileId, kind, name, seen.size() - 1, now));
        }
    }

    /** 单值列 → 一条清单（迁移未生效时的兜底） */
    private static List<AiModelProfileModelEntity> fallbackModels(AiModelProfileEntity profile) {
        List<AiModelProfileModelEntity> models = new ArrayList<>(2);
        Instant now = profile.getUpdatedAt() == null ? Instant.EPOCH : profile.getUpdatedAt();
        String chat = profile.getChatModel();
        if (chat != null && !chat.isBlank()) {
            models.add(new AiModelProfileModelEntity(profile.getId(),
                    AiModelProfileModelEntity.Kind.CHAT, chat.strip(), 0, now));
        }
        String embedding = profile.getEmbeddingModel();
        if (embedding != null && !embedding.isBlank()) {
            models.add(new AiModelProfileModelEntity(profile.getId(),
                    AiModelProfileModelEntity.Kind.EMBEDDING, embedding.strip(), 0, now));
        }
        return models;
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
     * @param name            展示名（必填）
     * @param presetId        预设标识；null 表示自定义
     * @param baseUrl         端点地址；空表示回落到环境变量
     * @param protocol        协议（只影响「获取模型列表」）
     * @param apiKey          密钥；**null 表示不改动**，空串表示清空（仅 update 有意义）
     * @param chatModel       单个对话模型名（V22 及以前的前端用；清单非空时被忽略）
     * @param temperature     温度；null 表示沿用服务端默认
     * @param embeddingModel  单个嵌入模型名（同上，兼容旧客户端）
     * @param chatModels      对话模型清单（V23）。**第一项即默认模型**，可为 null
     * @param embeddingModels 嵌入模型清单（V23）。第一项即默认，可为 null
     * @param audioModels     语音模型清单（V24）。第一项即默认，可为 null
     */
    public record SaveProfileCommand(String name,
                                     String presetId,
                                     String baseUrl,
                                     String protocol,
                                     String apiKey,
                                     String chatModel,
                                     Double temperature,
                                     String embeddingModel,
                                     List<String> chatModels,
                                     List<String> embeddingModels,
                                     List<String> audioModels) {

        /** 兼容构造器：不带语音模型（V23 及以前的调用方与测试） */
        public SaveProfileCommand(String name,
                                  String presetId,
                                  String baseUrl,
                                  String protocol,
                                  String apiKey,
                                  String chatModel,
                                  Double temperature,
                                  String embeddingModel,
                                  List<String> chatModels,
                                  List<String> embeddingModels) {
            this(name, presetId, baseUrl, protocol, apiKey, chatModel, temperature, embeddingModel,
                    chatModels, embeddingModels, null);
        }

        /** 兼容构造器：只给单个模型名（V22 及以前的调用方与测试） */
        public SaveProfileCommand(String name, String presetId, String baseUrl, String protocol,
                                  String apiKey, String chatModel, Double temperature,
                                  String embeddingModel) {
            this(name, presetId, baseUrl, protocol, apiKey, chatModel, temperature, embeddingModel,
                    null, null, null);
        }
    }
}
