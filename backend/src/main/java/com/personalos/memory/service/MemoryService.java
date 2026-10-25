package com.personalos.memory.service;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.text.Texts;
import com.personalos.memory.dto.MemoryDto;
import com.personalos.memory.entity.MemoryEntity;
import com.personalos.memory.repository.MemoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        String normalized = normalize(content);

        return repository.findFirstByContent(normalized)
                .map(existing -> {
                    log.info("记忆已存在，未重复写入 id={}", existing.getId());
                    return toDto(existing);
                })
                .orElseGet(() -> {
                    MemoryEntity saved = repository.save(new MemoryEntity(normalized, Instant.now()));
                    log.info("保存记忆 id={} 长度={}", saved.getId(), normalized.length());
                    return toDto(saved);
                });
    }

    /**
     * 编辑一条记忆。
     *
     * <p>判定顺序：标识格式 → 内容校验 → 存在性。内容非法时**不查库**，
     * 与阶段 7 的重命名保持同一顺序（contracts 判定顺序即契约）。
     *
     * <p>刻意不做「与其它条目重复」拦截：用户是在显式修改这一条，
     * 允许两条内容相同属于用户的选择，系统不去猜测意图。
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
        return toDto(repository.save(entity));
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
        List<MemoryEntity> memories = repository.findAllByOrderByUpdatedAtDesc();
        if (memories.isEmpty()) {
            return "";
        }

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
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
