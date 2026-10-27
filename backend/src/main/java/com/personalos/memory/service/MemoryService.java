package com.personalos.memory.service;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.text.Texts;
import com.personalos.memory.dto.MemoryDto;
import com.personalos.memory.entity.MemoryEntity;
import com.personalos.memory.entity.MemorySource;
import com.personalos.memory.repository.MemoryRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 长期记忆的写路径、查询与提示词渲染。
 *
 * <p>所有写操作都在这一层（宪法原则 III）；Controller 不接触仓储。
 *
 * <h2>本类的三条硬约束</h2>
 * <ol>
 *   <li><b>内容归一后再判定</b>：长度上限按 {@code strip()} 后的字符数算，
 *       否则「前后各 100 空格 + 1900 字」这种输入会被误拒。</li>
 *   <li><b>显式写入幂等</b>：内容与既有条目完全相同时不新增，直接返回既有条目。
 *       模型在长对话里重复调用保存是常态，不去重会让同一件事占满注入预算。</li>
 *   <li><b>注入不截断单条</b>：渲染时若加入某条会超出字符预算，就**整条跳过并停止**。
 *       截断会产生语义残缺的约束，而残缺的约束会被模型当成完整约束执行（research R-05）。</li>
 * </ol>
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

    /** 记忆标识格式：与库中 uuid 文本形式对齐，同时挡掉路径片段与不可见字符 */
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9-]{1,64}");

    /** 注入块的引导句：显式告诉模型这段不是用户输入，而是必须遵守的长期约束 */
    static final String PROMPT_HEADER = """
            ## 长期记忆

            以下是用户明确要求你长期记住的信息，在所有回答中都应遵守：
            """;

    private final MemoryRepository repository;

    public MemoryService(MemoryRepository repository) {
        this.repository = repository;
    }

    /** 全部记忆，按最近修改倒序（与注入顺序一致） */
    @Transactional(readOnly = true)
    public List<MemoryDto> list() {
        return repository.findAllByOrderByUpdatedAtDesc().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * 新增一条记忆；内容重复时不新增，返回既有条目（幂等）。
     *
     * @param content 记忆原文
     * @return 落库后（或命中的既有）条目
     */
    @Transactional
    public MemoryDto create(String content) {
        return create(content, MemorySource.MANUAL);
    }

    /**
     * 自动沉淀写入（阶段 22）：来源标记为 {@link MemorySource#AUTO}。
     *
     * <p>与手工写入共用同一套归一、幂等与长度校验 ——
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
        String contentHash = com.personalos.common.json.JsonCodec.sha256Hex(normalized);

        return repository.findByContentHash(contentHash)
                .map(existing -> {
                    log.info("记忆已存在，未重复写入 id={}", existing.getId());
                    return toDto(existing);
                })
                .orElseGet(() -> {
                    try {
                        MemoryEntity saved = repository.save(new MemoryEntity(normalized, Instant.now(), source));
                        log.info("保存记忆 id={} 长度={} 来源={}", saved.getId(), normalized.length(), source);
                        return toDto(saved);
                    } catch (DataIntegrityViolationException ex) {
                        // 并发去重（V17 唯一键）：两个请求同时查到「不存在」、同时 save，
                        // 后一个撞 uk_memory_content_hash。此时返回先落库的那条，行为与幂等命中一致
                        MemoryEntity winner = repository.findByContentHash(contentHash)
                                .orElseThrow(() -> ex);
                        log.info("记忆并发去重命中 id={}", winner.getId());
                        return toDto(winner);
                    }
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
     */
    @Transactional
    public MemoryDto update(String id, String content) {
        UUID memoryId = parseId(id);
        String normalized = normalize(content);

        MemoryEntity entity = repository.findById(memoryId)
                .orElseThrow(() -> new ResourceNotFoundException("记忆不存在：" + id));
        entity.setContent(normalized);
        entity.touch(Instant.now());
        log.info("修改记忆 id={} 长度={}", id, normalized.length());
        try {
            return toDto(repository.save(entity));
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "已存在内容相同的记忆，请直接修改那条或取消本次编辑");
        }
    }

    /** 删除一条记忆（不做去重/软删：存的就是用户显式要求，删了就该彻底消失） */
    @Transactional
    public void delete(String id) {
        UUID memoryId = parseId(id);
        if (!repository.existsById(memoryId)) {
            throw new ResourceNotFoundException("记忆不存在：" + id);
        }
        repository.deleteById(memoryId);
        log.info("删除记忆 id={}", id);
    }

    /**
     * 渲染注入到系统提示的记忆块。
     *
     * <p>无记忆时返回空串 —— 调用方据此**完全不追加**任何内容，连标题都不出现，
     * 这样「没有记忆」时送给模型的系统提示与阶段 1 逐字符相同（SC-004）。
     *
     * @return 可直接拼在系统提示后的文本；无记忆时为空串
     */
    @Transactional(readOnly = true)
    public String renderForPrompt() {
        return renderForPrompt("");
    }

    /**
     * 按「与本轮问题的相关性」挑选记忆并渲染（2026-10-26）。
     *
     * <h2>为什么不能只按 updated_at 倒序</h2>
     * 原先的实现是「最近修改的 30 条全塞」。问题是长期记忆是一组**互不相关**的约束：
     * 用户问日程时，同样会收到「周报格式」「代码风格」等一堆无关条目 ——
     * 既占掉预算，也稀释模型对真正相关内容（如「我下午三点后不看会」）的注意力。
     *
     * <h2>打分方式：字符二元组重叠</h2>
     * 不引入分词器与嵌入调用，用查询与记忆的**中文二元组**重叠率当相关度。
     * 选择它的理由是确定性（同样的输入永远同样的结果、可单元测试）与零依赖
     * （本模块不该为了排序去依赖知识库的嵌入栈）。
     * 局限同样明确：同义改写命中不了（「交周报」vs「周报提交」），
     * 所以排序只是**择优**，不改变「预算内全量注入」的基本行为。
     *
     * @param userInput 本轮用户消息；为空或只有一个字时退化为「最近修改优先」
     * @return 可直接拼在系统提示后的文本；无记忆时为空串
     */
    @Transactional(readOnly = true)
    public String renderForPrompt(String userInput) {
        // 候选窗口比注入上限大得多：只有先把「可能相关的」都捞出来，排序才有意义。
        // 120 条 × 单条 ≤2000 字 ≈ 最坏 240 KB/轮，个人规模下可接受；再大就该上向量检索了。
        List<MemoryEntity> candidates = repository.findByOrderByUpdatedAtDesc(PageRequest.of(0, INJECT_CANDIDATE_LIMIT));
        if (candidates.isEmpty()) {
            return "";
        }
        List<MemoryEntity> memories = rankByRelevance(candidates, userInput);

        StringBuilder block = new StringBuilder(PROMPT_HEADER).append('\n');
        int included = 0;
        int chars = 0;
        for (MemoryEntity memory : memories) {
            if (included >= INJECT_MAX_COUNT) {
                break;
            }
            int length = memory.getContent().length();
            if (chars + length > INJECT_MAX_CHARS) {
                // 整条跳过并停止：不写半条记忆（残缺约束比缺失更危险）
                break;
            }
            included++;
            chars += length;
            block.append(included).append(". ").append(memory.getContent()).append('\n');
        }

        if (included == 0) {
            return "";
        }
        return block.toString().stripTrailing();
    }

    /**
     * 按与查询的相关度重排；得分相同则保持传入顺序（即最近修改优先）。
     *
     * <p>用稳定排序实现「相关度为主、新近度为辅」的平手规则 ——
     * 不需要引入权重常量去调和两个量纲不同的分数。
     */
    private List<MemoryEntity> rankByRelevance(List<MemoryEntity> candidates, String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return candidates;
        }
        // 只取前 200 字参与打分：用户消息上限 4000 字，长文会让重叠率分母失真
        String query = userInput.length() > RELEVANCE_QUERY_LIMIT
                ? userInput.substring(0, RELEVANCE_QUERY_LIMIT)
                : userInput;
        Set<String> queryGrams = bigrams(query);
        if (queryGrams.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(
                        (MemoryEntity memory) -> overlapRatio(queryGrams, bigrams(memory.getContent()))).reversed())
                .toList();
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
    private static Set<String> bigrams(String text) {
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
                entity.getSource().name(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
