package com.personalos.task.service;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.api.PageDto;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.text.Texts;
import com.personalos.task.TaskTags;
import com.personalos.task.dto.CreateTaskRequest;
import com.personalos.task.dto.TaskDto;
import com.personalos.task.dto.TaskQuery;
import com.personalos.task.dto.UpdateTaskRequest;
import com.personalos.task.entity.TaskEntity;
import com.personalos.task.entity.TaskPriority;
import com.personalos.task.entity.TaskStatus;
import com.personalos.task.repository.TaskRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 任务写路径与查询。
 *
 * <p>所有写操作都在这里（宪法原则 III）。Agent 工具是**适配器**，
 * 内部调用本服务，不直接接触仓储。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** 单次批量操作上限（FR-006）。超过则要求拆分，而不是静默截断 */
    public static final int MAX_BULK_SIZE = 50;

    /**
     * 展示顺序：未完成在前 → 截止时间早的在前（无截止时间排最后）→ 新建的在前。
     *
     * <p>排序放在内存里做，而不是 SQL：因为「未完成优先」需要一个 CASE 表达式，
     * 而条件筛选已经用 Specification 表达。个人任务规模（千级以内）下内存排序成本可忽略，
     * 且行为完全确定、可单元测试。
     *
     * <p><b>为什么分页后仍然不做 SQL 排序</b>（2026-10-14 分页落地时的复核）：一旦引入
     * SQL 排序，就会出现「SQL 一套顺序、内存 comparator 一套顺序」两个真相，
     * 二者任何细微差别（如 NULLS LAST 的方言差异）都会让翻页出现重复或漏项。
     * 分页只负责切片，顺序仍然只有这一个 comparator。真有规模问题时的正确做法是
     * **把排序整体搬进 SQL 并删掉这个 comparator**，而不是两套并存。
     */
    private static final Comparator<TaskEntity> DISPLAY_ORDER = Comparator
            .comparingInt((TaskEntity task) -> task.getStatus() == TaskStatus.DONE ? 1 : 0)
            .thenComparing(TaskEntity::getDueAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TaskEntity::getCreatedAt, Comparator.reverseOrder());

    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional
    public TaskDto create(CreateTaskRequest request) {
        String title = Texts.requireNotBlank(request.title(), "标题", Texts.TITLE_MAX_LENGTH);
        String description = Texts.trimToNull(request.description());
        TaskPriority priority = request.priority() == null ? TaskPriority.MEDIUM : request.priority();

        TaskEntity task = new TaskEntity(title, description, priority, request.dueAt(), Instant.now());
        task.setTags(TaskTags.normalize(request.tags()));
        TaskEntity saved = taskRepository.save(task);
        log.info("创建任务 id={} title={}", saved.getId(), saved.getTitle());
        return toDto(saved, Instant.now());
    }

    /**
     * 查询任务列表（分页）。
     *
     * <p>顺序由 {@link #DISPLAY_ORDER} 决定，分页只切片——「有多少」与「这一页是什么」
     * 由 {@link PageDto} 一并返回，不再有静默截断。
     */
    @Transactional(readOnly = true)
    public PageDto<TaskDto> search(TaskQuery query) {
        List<TaskEntity> matched = taskRepository.findAll(buildSpecification(query));
        Instant now = Instant.now();
        List<TaskDto> ordered = matched.stream()
                .sorted(DISPLAY_ORDER)
                .map(task -> toDto(task, now))
                .toList();
        return PageDto.of(ordered, query.page(), query.size());
    }

    /**
     * 全部任务，顺序与界面一致（导出用，阶段 12）。
     *
     * <p>不走 {@link #search} 是因为它受分页上限约束（{@code MAX_SIZE=100}），
     * 拿它做导出会在任务超过 100 条时**静默少导**。这里显式返回全量，
     * 并复用 {@link #DISPLAY_ORDER} 让文件里的顺序与用户看到的一致。
     */
    @Transactional(readOnly = true)
    public List<TaskDto> listAll() {
        Instant now = Instant.now();
        return taskRepository.findAll().stream()
                .sorted(DISPLAY_ORDER)
                .map(task -> toDto(task, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskDto get(UUID id) {
        return toDto(requireTask(id), Instant.now());    }

    @Transactional(readOnly = true)
    public List<TaskDto> findByIds(List<UUID> ids) {
        Instant now = Instant.now();
        return taskRepository.findAllById(ids).stream()
                .sorted(DISPLAY_ORDER)
                .map(task -> toDto(task, now))
                .toList();
    }

    @Transactional
    public TaskDto update(UUID id, UpdateTaskRequest request) {
        TaskEntity task = requireTask(id);
        Instant now = Instant.now();

        if (request.title() != null) {
            task.setTitle(Texts.requireNotBlank(request.title(), "标题", Texts.TITLE_MAX_LENGTH));
        }
        if (request.description() != null) {
            task.setDescription(Texts.trimToNull(request.description()));
        }
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        if (request.status() != null) {
            task.setStatus(request.status(), now);
        }
        // 截止时间的「设置」与「清除」必须可区分：仅凭 record 的 null 无法表达
        // 「字段未提供」与「显式置空」，故由 clearDueAt 显式表达清除意图
        if (request.dueAt() != null) {
            task.setDueAt(request.dueAt());
        } else if (Boolean.TRUE.equals(request.clearDueAt())) {
            task.setDueAt(null);
        }
        // 标签：null = 未提供（保持不变），空数组 = 显式清空。
        // 与 dueAt 同一套约定 —— 数组本身就能表达「清空」，不需要再造一个 clearTags 标志。
        if (request.tags() != null) {
            task.setTags(TaskTags.normalize(request.tags()));
        }

        task.touch(now);
        return toDto(taskRepository.save(task), now);
    }

    @Transactional
    public TaskDto setCompleted(UUID id, boolean completed) {
        TaskEntity task = requireTask(id);
        Instant now = Instant.now();
        task.setStatus(completed ? TaskStatus.DONE : TaskStatus.TODO, now);
        task.touch(now);
        log.info("任务完成状态变更 id={} completed={}", id, completed);
        return toDto(taskRepository.save(task), now);
    }

    /**
     * 修改截止时间（关键字段，Agent 通道需人工确认后才会走到这里）。
     *
     * @param dueAt 新截止时间；{@code null} 表示清除
     */
    @Transactional
    public TaskDto reschedule(UUID id, Instant dueAt) {
        TaskEntity task = requireTask(id);
        Instant now = Instant.now();
        task.setDueAt(dueAt);
        task.touch(now);
        return toDto(taskRepository.save(task), now);
    }

    /**
     * 修改优先级（关键字段，Agent 通道需人工确认后才会走到这里）。
     */
    @Transactional
    public TaskDto changePriority(UUID id, TaskPriority priority) {
        TaskEntity task = requireTask(id);
        Instant now = Instant.now();
        task.setPriority(priority);
        task.touch(now);
        return toDto(taskRepository.save(task), now);
    }

    @Transactional
    public void delete(UUID id) {
        TaskEntity task = requireTask(id);
        taskRepository.delete(task);
        log.info("删除任务 id={} title={}", id, task.getTitle());
    }

    /**
     * 批量删除。
     *
     * <p>在一个事务内完成，保证「要么全删要么不删」，不留下部分成功的假象。
     *
     * @return 实际删除的行数
     */
    @Transactional
    public int deleteAll(List<UUID> ids) {
        List<UUID> distinctIds = ids == null ? List.of() : ids.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return 0;
        }
        if (distinctIds.size() > MAX_BULK_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "一次最多处理 " + MAX_BULK_SIZE + " 个任务，请拆分后重试");
        }
        List<TaskEntity> found = taskRepository.findAllById(distinctIds);
        taskRepository.deleteAllInBatch(found);
        log.info("批量删除任务 请求={} 实际删除={}", distinctIds.size(), found.size());
        return found.size();
    }

    /**
     * 取出截止时间落在 {@code [from, to]} 且**尚未完成**的任务。
     *
     * <p>供提醒扫描使用（{@code calendar} → {@code task} 的只读依赖）。
     * 已完成的任务刻意排除：已完成的事不该再提醒用户。
     */
    @Transactional(readOnly = true)
    public List<TaskDto> findDueBetween(Instant from, Instant to) {
        Specification<TaskEntity> specification = (root, query, builder) -> builder.and(
                builder.isNotNull(root.get("dueAt")),
                builder.greaterThanOrEqualTo(root.get("dueAt"), from),
                builder.lessThanOrEqualTo(root.get("dueAt"), to),
                builder.notEqual(root.get("status"), TaskStatus.DONE));

        Instant now = Instant.now();
        return taskRepository.findAll(specification).stream()
                .sorted(Comparator.comparing(TaskEntity::getDueAt))
                .map(task -> toDto(task, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public long countAll() {
        return taskRepository.count();
    }

    /**
     * 取回任务实体；不存在时抛「资源不存在」（FR-016）。
     */
    @Transactional(readOnly = true)
    public TaskEntity requireTask(UUID id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "任务标识不能为空");
        }
        return taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("任务不存在：" + id));
    }

    private Specification<TaskEntity> buildSpecification(TaskQuery query) {
        return (root, criteriaQuery, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.status() != null) {
                predicates.add(builder.equal(root.get("status"), query.status()));
            }
            if (StringUtils.hasText(query.keyword())) {
                String pattern = "%" + query.keyword().strip().toLowerCase() + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("title")), pattern),
                        builder.like(builder.lower(builder.coalesce(root.get("description"), "")), pattern)));
            }
            if (query.tag() != null) {
                // join 到 element collection：同一任务里同一个标签只会存一条，
                // 因此 equal 条件最多命中一行，不会让结果集出现重复任务
                predicates.add(builder.equal(root.join("tags"), query.tag()));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private TaskDto toDto(TaskEntity task, Instant now) {
        boolean overdue = task.getStatus() != TaskStatus.DONE
                && task.getDueAt() != null
                && task.getDueAt().isBefore(now);
        return new TaskDto(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus().name(),
                task.getPriority().name(),
                task.getTags(),
                task.getDueAt(),
                task.getCompletedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                overdue);
    }

    /**
     * 全部标签（去重、字典序）。
     *
     * <p>供前端筛选下拉使用。删除任务会级联删标签，因此不会出现「点进去没有结果」的孤儿标签。
     */
    @Transactional(readOnly = true)
    public List<String> listTags() {
        return taskRepository.findDistinctTags();
    }
}
