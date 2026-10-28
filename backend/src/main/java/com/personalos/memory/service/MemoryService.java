package com.personalos.memory.service;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.json.JsonCodec;
import com.personalos.common.text.Texts;
import com.personalos.memory.MemoryProperties;
import com.personalos.memory.dto.MemoryDto;
import com.personalos.memory.entity.MemoryEntity;
import com.personalos.memory.entity.MemorySource;
import com.personalos.memory.repository.MemoryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 长期记忆的写路径、查询与提示词渲染。
 *
 * <p>所有写操作都在这一层（宪法原则 III）；Controller 不接触仓储。
 *
 * <h2>本类的四条硬约束</h2>
 * <ol>
 *   <li><b>内容归一后再判定</b>：长度上限按 {@code strip()} 后的字符数算，
 *       否则「前后各 100 空格 + 1900 字」这种输入会被误拒。</li>
 *   <li><b>显式写入幂等</b>：内容与既有条目完全相同时不新增，直接返回既有条目。
 *       模型在长对话里重复调用保存是常态，不去重会让同一件事占满注入预算。</li>
 *   <li><b>注入不截断单条</b>：渲染时若加入某条会超出字符预算，就**整条跳过并停止**。
 *       截断会产生语义残缺的约束，而残缺的约束会被模型当成完整约束执行（research R-05）。</li>
 *   <li><b>相似度只改顺序，不改集合</b>（V26）：向量用于排序与近重复判定，
 *       **不用于召回** —— 记忆是约束性事实，按相似度取 topN 会在「对话没提到它」时
 *       漏掉必须生效的约束（specs/008 research R-03）。需要「永远生效」的条目由
 *       {@code pinned} 表达。</li>
 * </ol>
 *
 * <h2>事务边界</h2>
 * 写入路径用显式 {@link TransactionTemplate}：嵌入调用是一次 HTTP（几百毫秒到数秒），
 * 绝不能放在事务里占着 Hikari 连接（池只有 10 条，见 {@code ChatSummaryService} 的同款教训）。
 * 所以顺序恒为「事务外算向量 → 事务内落库 → 事务内写向量列」。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** 单条记忆的长度上限（归一后字符数） */
    public static final int CONTENT_MAX_LENGTH = 2000;

    /** 注入条数上限 */
    public static final int INJECT_MAX_COUNT = 30;

    /** 注入累计字符上限（只算条目正文，不含标题与编号） */
    public static final int INJECT_MAX_CHARS = 6000;

    /**
     * 相关性排序的候选窗口（2026-10-26）。
     *
     * <p>必须显著大于 {@link #INJECT_MAX_COUNT}：只捞 30 条再排序，本质仍是
     * 「最近修改的 30 条里挑」；窗口放大后才能把很久以前但恰好相关的条目捞出来。
     */
    public static final int INJECT_CANDIDATE_LIMIT = 120;

    /** 参与相关度打分的查询长度上限：用户消息可达 4000 字，全量打分会让重叠率分母失真 */
    private static final int RELEVANCE_QUERY_LIMIT = 200;

    /**
     * 命中记录的刷新间隔。
     *
     * <p>渲染路径每轮都会被调用，而 {@code ChatPromptAssembler.estimatePromptChars}
     * （仪表盘估算）也会调它 —— 不设间隔的话，光是打开页面就能把计数刷高。
     * 这个字段只用于回答「这条记忆到底有没有用」，十分钟的精度足够了。
     */
    private static final Duration USAGE_REFRESH_GAP = Duration.ofMinutes(10);

    /** 记忆标识格式：与库中 uuid 文本形式对齐，同时挡掉路径片段与不可见字符 */
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9-]{1,64}");

    /** 注入块的引导句：显式告诉模型这段不是用户输入，而是必须遵守的长期约束 */
    static final String PROMPT_HEADER = """
            ## 长期记忆

            以下是用户明确要求你长期记住的信息，在所有回答中都应遵守：
            """;

    private final MemoryRepository repository;
    private final MemoryEmbeddingService embeddingService;
    private final MemoryEmbeddingStore embeddingStore;
    private final MemoryProperties properties;

    /** 写事务（创建、修改、置顶、命中记录共用；命中记录失败不能影响对话） */
    private final TransactionTemplate writeTransaction;

    public MemoryService(MemoryRepository repository,
                         MemoryEmbeddingService embeddingService,
                         MemoryEmbeddingStore embeddingStore,
                         MemoryProperties properties,
                         PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.embeddingService = embeddingService;
        this.embeddingStore = embeddingStore;
        this.properties = properties;
        // 显式模板而不是 @Transactional：create 里要「先事务外算向量」，
        // 注解只能把整个方法包进事务，做不到这种分段（且同类自调用会静默失效）。
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 全部记忆，按「常驻优先、再按最近修改」倒序。
     *
     * <p>与注入顺序保持一致：用户看到的第一条，就是预算紧张时最后被挤掉的那条。
     */
    @Transactional(readOnly = true)
    public List<MemoryDto> list() {
        return repository.findAllByOrderByPinnedDescUpdatedAtDesc().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * 新增一条记忆；内容重复时不新增，返回既有条目（幂等）。
     *
     * @param content 记忆原文
     * @return 落库后（或命中的既有）条目
     */
    public MemoryDto create(String content) {
        return create(content, MemorySource.MANUAL);
    }

    /**
     * 自动沉淀写入（阶段 22）：来源标记为 {@link MemorySource#AUTO}。
     *
     * <p>与手工写入共用同一套归一、幂等、长度校验与近重复取代 ——
     * 模型写进来的内容不该有「另一套规则」，否则清理脏数据时会更麻烦。
     *
     * <p>幂等命中已有内容时**不修改其来源**：已经存在的条目，来源以最初那次写入为准。
     *
     * @param content 待沉淀的陈述
     * @return 落库后的视图（命中幂等时返回既有那条）
     */
    public MemoryDto createAuto(String content) {
        return create(content, MemorySource.AUTO);
    }

    private MemoryDto create(String content, MemorySource source) {
        String normalized = normalize(content);
        String contentHash = JsonCodec.sha256Hex(normalized);

        Optional<MemoryEntity> existing = repository.findByContentHash(contentHash);
        if (existing.isPresent()) {
            log.info("记忆已存在，未重复写入 id={}", existing.get().getId());
            return toDto(existing.get());
        }

        // 事务外：一次嵌入 HTTP。算不出来就照常保存，只是这条暂时不参与相关度排序
        Optional<float[]> vector = embeddingService.embed(normalized);

        return writeTransaction.execute(status -> {
            MemoryEntity entity = new MemoryEntity(normalized, Instant.now(), source);
            MemoryEntity saved;
            try {
                // saveAndFlush：下面写向量列是一条原生 UPDATE，行必须先真实存在于库里
                saved = repository.saveAndFlush(entity);
            } catch (DataIntegrityViolationException ex) {
                // 并发去重（V17 唯一键）：另一个请求先落了同内容的行，返回它
                MemoryEntity winner = repository.findByContentHash(contentHash).orElseThrow(() -> ex);
                log.info("记忆并发去重命中 id={}", winner.getId());
                return toDto(winner);
            }
            applyVector(saved, vector);
            log.info("保存记忆 id={} 长度={} 来源={} 常驻={} 向量={}",
                    saved.getId(), normalized.length(), source, saved.isPinned(), vector.isPresent());
            return toDto(saved);
        });
    }

    /**
     * 编辑一条记忆。
     *
     * <p>判定顺序：标识格式 → 内容校验 → 存在性。内容非法时**不查库**，
     * 与阶段 7 的重命名保持同一顺序（contracts 判定顺序即契约）。
     *
     * <p>V17 起加了 content_hash 唯一键，「改成与另一条完全相同」会被数据库拒绝——
     * 这里把约束冲突转成明确提示，而不是让用户看到 500。
     *
     * <p>V26：内容变了旧向量就失效，先作废再重算。不作废的话相似度会拿旧文本的
     * 向量去比新文本，排序结果看似正常但完全错位。
     */
    public MemoryDto update(String id, String content) {
        UUID memoryId = parseId(id);
        String normalized = normalize(content);
        Optional<float[]> vector = embeddingService.embed(normalized);

        return writeTransaction.execute(status -> {
            MemoryEntity entity = repository.findById(memoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("记忆不存在：" + id));
            entity.setContent(normalized);
            entity.touch(Instant.now());
            entity.invalidateEmbedding();

            MemoryEntity saved;
            try {
                saved = repository.saveAndFlush(entity);
            } catch (DataIntegrityViolationException ex) {
                throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                        "已存在内容相同的记忆，请直接修改那条或取消本次编辑");
            }

            if (vector.isPresent()) {
                applyVector(saved, vector);
            } else {
                // 没有新向量就不要留着旧向量：它对应的是上一版内容
                embeddingStore.clear(memoryId);
            }
            log.info("修改记忆 id={} 长度={} 向量={}", id, normalized.length(), vector.isPresent());
            return toDto(saved);
        });
    }

    /**
     * 置顶 / 取消置顶。
     *
     * <p>刻意**不因超过上限而拒绝**：用户点置顶就该生效，拒绝只会让人困惑。
     * 超出部分在注入时按「常驻上限」截断（保留最近更新的若干条），并记录日志。
     *
     * @param id     记忆标识
     * @param pinned 是否常驻
     * @return 更新后的视图
     */
    public MemoryDto setPinned(String id, boolean pinned) {
        UUID memoryId = parseId(id);
        return writeTransaction.execute(status -> {
            MemoryEntity entity = repository.findById(memoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("记忆不存在：" + id));
            entity.setPinned(pinned);
            MemoryEntity saved = repository.save(entity);
            long count = repository.countByPinnedTrueAndSupersededByIsNull();
            if (pinned && count > properties.pinnedMaxCount()) {
                log.info("常驻记忆数 {} 超过上限 {}，注入时按最近更新保留前 {} 条",
                        count, properties.pinnedMaxCount(), properties.pinnedMaxCount());
            }
            log.info("记忆常驻状态变更 id={} pinned={}", id, pinned);
            return toDto(saved);
        });
    }

    /** 删除一条记忆（不做去重/软删：存的就是用户显式要求，删了就该彻底消失） */
    public void delete(String id) {
        UUID memoryId = parseId(id);
        writeTransaction.executeWithoutResult(status -> {
            if (!repository.existsById(memoryId)) {
                throw new ResourceNotFoundException("记忆不存在：" + id);
            }
            repository.deleteById(memoryId);
            log.info("删除记忆 id={}", id);
        });
    }

    /**
     * 渲染注入到系统提示的记忆块（不做相关性筛选，按常驻 + 最近修改）。
     *
     * <p>无记忆时返回空串 —— 调用方据此**完全不追加**任何内容，连标题都不出现，
     * 这样「没有记忆」时送给模型的系统提示与阶段 1 逐字符相同（SC-004）。
     *
     * @return 可直接拼在系统提示后的文本；无记忆时为空串
     */
    public String renderForPrompt() {
        return renderForPrompt("");
    }

    /**
     * 按「常驻优先 + 与本轮问题的相关性」挑选记忆并渲染（V26）。
     *
     * <h2>为什么不能只按 updated_at 倒序</h2>
     * 长期记忆是一组**互不相关**的约束：用户问日程时，同样会收到「周报格式」
     * 「代码风格」等一堆无关条目 —— 既占掉预算，也稀释模型对真正相关内容
     * （如「我下午三点后不看会」）的注意力。
     *
     * <h2>为什么「相关」不能决定「是否注入」</h2>
     * 记忆是约束性事实，不是可被召回的资料。对话里没提到「周报」时，
     * 「周报每周五交」仍然必须生效（specs/008 research R-03）。
     * 因此相关度**只决定先放谁**：预算内仍然全量注入，集合不缺项。
     * 真正需要「永不缺项」的条目由 {@code pinned} 表达。
     *
     * <h2>三档相关度来源</h2>
     * <ol>
     *   <li>向量余弦（记忆有向量且模型同名）—— 能命中同义改写；</li>
     *   <li>中文字符二元组重叠 —— 向量不可用/超时时的确定性兜底；</li>
     *   <li>都没有 —— 退化为「最近修改优先」，仍不影响注入集合。</li>
     * </ol>
     *
     * @param userInput 本轮用户消息；为空或只有一个字时退化为「最近修改优先」
     * @return 可直接拼在系统提示后的文本；无记忆时为空串
     */
    public String renderForPrompt(String userInput) {
        // 候选窗口比注入上限大得多：只有先把「可能相关的」都捞出来，排序才有意义。
        // 120 条 × 单条 ≤2000 字 ≈ 最坏 240 KB/轮，个人规模下可接受；再大就该上向量检索了。
        List<MemoryEntity> candidates = repository.findBySupersededByIsNullOrderByUpdatedAtDesc(
                PageRequest.of(0, INJECT_CANDIDATE_LIMIT));
        if (candidates.isEmpty()) {
            return "";
        }
        List<MemoryEntity> ordered = orderForInjection(candidates, userInput);

        StringBuilder block = new StringBuilder(PROMPT_HEADER).append('\n');
        List<MemoryEntity> injected = new ArrayList<>();
        int chars = 0;
        for (MemoryEntity memory : ordered) {
            if (injected.size() >= INJECT_MAX_COUNT) {
                break;
            }
            int length = memory.getContent().length();
            if (chars + length > INJECT_MAX_CHARS) {
                // 整条跳过并停止：不写半条记忆（残缺约束比缺失更危险）
                break;
            }
            chars += length;
            injected.add(memory);
            block.append(injected.size()).append(". ").append(memory.getContent()).append('\n');
        }

        if (injected.isEmpty()) {
            return "";
        }
        recordUsage(injected);
        return block.toString().stripTrailing();
    }

    /**
     * 定序：常驻在前，其余按得分降序。
     *
     * <p>常驻条目**先占预算**是刻意的：它们的语义就是「不允许被挤掉」。
     * 若混在一起按得分排，一条很旧但用户置顶的硬约束会被一批新近的高分条目挤出去。
     */
    private List<MemoryEntity> orderForInjection(List<MemoryEntity> candidates, String userInput) {
        int pinnedMax = properties.pinnedMaxCount();
        List<MemoryEntity> pinned = new ArrayList<>();
        List<MemoryEntity> rest = new ArrayList<>();
        for (MemoryEntity memory : candidates) {
            if (memory.isPinned()) {
                pinned.add(memory);
            } else {
                rest.add(memory);
            }
        }

        // 超出上限的常驻条目降级为普通条目参与排序，而不是直接丢弃 ——
        // 丢一条用户明确置顶的内容，比排得靠后糟得多
        if (pinned.size() > pinnedMax) {
            rest.addAll(pinned.subList(pinnedMax, pinned.size()));
            pinned = pinned.subList(0, pinnedMax);
        }

        List<MemoryEntity> ordered = new ArrayList<>(pinned);
        ordered.addAll(rankByRelevance(rest, userInput));
        return ordered;
    }

    /**
     * 按得分重排非常驻条目。
     *
     * <p>得分为「相关度 0.7 + 新近度 0.3」。新近度用半衰期衰减：
     * 半年没动过的条目不会因为「当时排前面」而永久占位。
     * 平手时按最近修改排在前面（显式写出来，不依赖排序稳定性）。
     */
    private List<MemoryEntity> rankByRelevance(List<MemoryEntity> candidates, String userInput) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        Optional<float[]> queryVector = queryVector(userInput);
        Map<UUID, Double> similarities = queryVector
                .map(vector -> embeddingStore.similarityTo(vector, embeddingService.modelTag(), candidates.size()))
                .orElse(Map.of());
        Set<String> queryGrams = textGrams(userInput);
        Instant now = Instant.now();

        return candidates.stream()
                .sorted(Comparator
                        .comparingDouble((MemoryEntity memory) -> score(memory, similarities, queryGrams, now))
                        .reversed()
                        .thenComparing(MemoryEntity::getUpdatedAt, Comparator.reverseOrder()))
                .toList();
    }

    /** 单条得分：相关度 0.7 + 新近度 0.3 */
    private double score(MemoryEntity memory, Map<UUID, Double> similarities, Set<String> queryGrams, Instant now) {
        double relevance = 0;
        // id 为 null 只在「尚未落库的对象」上出现（单测直接构造实体）。
        // 这里显式挡住它：Map.of() 是不可变映射，get(null) 会抛 NPE ——
        // 一条还没落库的记忆不该让整轮渲染崩掉。
        Double similarity = memory.getId() == null ? null : similarities.get(memory.getId());
        if (similarity != null) {
            // 余弦可能因浮点误差略超出 [0,1]，夹一下保证它不会压过满分
            relevance = Math.max(0, Math.min(1, similarity));
        } else if (!queryGrams.isEmpty()) {
            relevance = overlapRatio(queryGrams, textGrams(memory.getContent()));
        }
        return 0.7 * relevance + 0.3 * recency(memory.getUpdatedAt(), now);
    }

    /**
     * 新近度（0~1）：半衰期后降到 0.5。
     *
     * <p>半衰期配成 0 表示不衰减，恒为 1 —— 此时得分等价于「只看相关度」。
     */
    private double recency(Instant updatedAt, Instant now) {
        int halfLifeDays = properties.ranking().halfLifeDays();
        if (halfLifeDays <= 0 || updatedAt == null) {
            return 1.0;
        }
        double days = Math.max(0, Duration.between(updatedAt, now).toMinutes() / (double) (60 * 24));
        return Math.pow(0.5, days / halfLifeDays);
    }

    /** 查询向量（带超时）；排序关闭或无输入时为空 */
    private Optional<float[]> queryVector(String userInput) {
        if (!properties.ranking().enabled() || userInput == null || userInput.isBlank()) {
            return Optional.empty();
        }
        // 只取前 200 字参与打分：用户消息上限 4000 字，长文会让重叠率分母失真
        String query = userInput.length() > RELEVANCE_QUERY_LIMIT
                ? userInput.substring(0, RELEVANCE_QUERY_LIMIT)
                : userInput;
        return embeddingService.embed(query, properties.ranking().timeout());
    }

    /**
     * 记录「这些条目真的被注入了」。
     *
     * <p>失败静默：这是一个纯统计字段，它写不进去不该让整轮对话失败。
     * 间隔判断放在比较 {@code last_used_at} 上而不是内存计时器 ——
     * 进程重启后仍然成立，也不需要额外状态。
     */
    private void recordUsage(List<MemoryEntity> injected) {
        Instant now = Instant.now();
        List<MemoryEntity> touched = new ArrayList<>();
        for (MemoryEntity memory : injected) {
            if (memory.getLastUsedAt() == null
                    || Duration.between(memory.getLastUsedAt(), now).compareTo(USAGE_REFRESH_GAP) >= 0) {
                memory.recordUsage(now);
                touched.add(memory);
            }
        }
        if (touched.isEmpty()) {
            return;
        }
        try {
            writeTransaction.executeWithoutResult(status -> repository.saveAll(touched));
        } catch (RuntimeException ex) {
            log.debug("记录记忆使用次数失败（不影响对话）：{}", ex.toString());
        }
    }

    /**
     * 写入向量并处理近重复取代。
     *
     * <p>顺序不能反：先查相似（此时新条目自己的向量还没写，不会被自己命中，
     * 但仍然显式排除自身 id），再写向量。反过来会让「找相似」把自己找出来。
     */
    private void applyVector(MemoryEntity saved, Optional<float[]> vector) {
        if (vector.isEmpty()) {
            return;
        }
        String model = embeddingService.modelTag();
        embeddingStore.findSimilar(vector.get(), model, properties.similarThreshold(), saved.getId())
                .ifPresent(match -> supersede(match.id(), saved));
        if (embeddingStore.save(saved.getId(), vector.get(), model)) {
            saved.markEmbedded(model, Instant.now());
            repository.saveAndFlush(saved);
        }
    }

    /**
     * 把旧条目标记为「被新条目取代」。
     *
     * <p>不物理删除：自动去重最危险的不是去重本身，而是**用户看不到它做了什么**。
     * 保留旧条目 + 指向新条目的链接，用户才能判断「这条该不该被合掉」。
     *
     * <p>常驻意图随内容转移：用户置顶的是「这件事」，不是「这一行记录」。
     * 不转移的话，改一次措辞就会让一条硬约束悄悄失去常驻资格。
     */
    private void supersede(UUID olderId, MemoryEntity newer) {
        repository.findById(olderId).ifPresent(older -> {
            if (!older.isActive()) {
                return;
            }
            older.setSupersededBy(newer.getId());
            if (older.isPinned()) {
                older.setPinned(false);
                newer.setPinned(true);
            }
            repository.save(older);
            log.info("记忆近重复取代：旧={} 新={}", olderId, newer.getId());
        });
    }

    /** 重叠率 = 查询二元组中出现在记忆里的比例（0~1） */
    private static double overlapRatio(Set<String> queryGrams, Set<String> memoryGrams) {
        long hit = queryGrams.stream().filter(memoryGrams::contains).count();
        return (double) hit / queryGrams.size();
    }

    /**
     * 中文/英文通用的字符二元组。
     *
     * <p>先去掉所有空白并转小写，再逐对取两字符。单字符输入得到空集合 ——
     * 一个字的查询没有可比对的二元组，此时调用方退化为时间排序而不是「全都不相关」。
     */
    private static Set<String> textGrams(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normalized = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    private String normalize(String content) {
        return Texts.requireNotBlank(content, "记忆内容", CONTENT_MAX_LENGTH);
    }

    /**
     * 解析并校验记忆标识。
     *
     * <p>先用正则挡形态，再转 UUID —— 只做后者会让 `../` 这类输入走到 UUID 解析异常，
     * 错误信息取决于异常实现，而不是我们的契约。
     */
    private UUID parseId(String raw) {
        if (raw == null || !ID_PATTERN.matcher(raw).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "记忆标识格式非法");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "记忆标识格式非法");
        }
    }

    private MemoryDto toDto(MemoryEntity entity) {
        return new MemoryDto(entity.getId().toString(), entity.getContent(),
                entity.getSource().name(), entity.isPinned(),
                entity.getSupersededBy() == null ? null : entity.getSupersededBy().toString(),
                entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getHitCount(), entity.getLastUsedAt());
    }
}
