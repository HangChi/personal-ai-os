package com.personalos.task.service;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.api.PageDto;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.search.SearchPatterns;
import com.personalos.common.text.Texts;
import com.personalos.task.TaskTags;
import com.personalos.task.dto.CreateTaskRequest;
import com.personalos.task.dto.TaskDto;
import com.personalos.task.dto.TaskQuery;
import com.personalos.task.dto.TaskStatsDto;
import com.personalos.task.dto.UpdateTaskRequest;
import com.personalos.task.entity.TaskEntity;
import com.personalos.task.entity.TaskPriority;
import com.personalos.task.entity.TaskStatus;
import com.personalos.task.repository.TaskRepository;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
     * <p><b>排序已整体下推到 SQL</b>（2026-10-28，审查第 10 项）。原先这里是内存
     * comparator，先 {@code findAll} 全量再排序切片；任务涨到几千条后，每翻一页都要
     * 把整表读进内存排一遍。现在由 {@link #applyDisplayOrder} 翻译成 ORDER BY，
     * 内存 comparator 已删除 —— 删掉而不是留着「双保险」，因为两套顺序一旦有细微差别
     * （NULLS LAST 的方言差异、稳定排序的兜底键不同），翻页就会重复或漏项，那种 bug
     * 极难复现。顺序只有一处真相，就是这段 SQL。
     *
     * <p>注意「未完成优先」不能用 {@code ORDER BY status} 实现：status 是 varchar，
     * 字母序给出 {@code DOING < DONE < TODO}，已完成的会排在待办前面，与界面完全相反。
     * 必须用 CASE 表达式显式映射（见 {@link #applyDisplayOrder}）。
     */
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
     * <p>排序与切片都在 SQL 完成（见 {@link #applyDisplayOrder} 与
     * {@link PageDto#ofPage}）：只把当前页的实体读进内存，而不是先全量加载再切。
     *
     * <p>{@code total} 用独立 count 查询而不是本页条数——前端要靠它决定还有没有下一页。
     * 这里刻意<b>不</b>把 count 与 list 合成一条窗口函数查询：Spring Data 的
     * Specification 分页接口已经为这两件事各提供一条路径，合成一条需要放弃
     * {@code JpaSpecificationExecutor} 去手写原生 SQL，筛选条件就得复制一份。
     * 两害相权，多一次 count 往返更划算，也不会出现「条件写两份、改一处忘一处」。
     */
    @Transactional(readOnly = true)
    public PageDto<TaskDto> search(TaskQuery query) {
        Specification<TaskEntity> filter = buildSpecification(query);

        // 极大页码必须在进入 SQL 之前挡掉，不能指望底层报错或自己收敛。
        //
        // 这正是 ErrorEnvelopeIntegrationTest#hugePageNumberReturnsEmptyPage 盯着的场景：
        // page=21474837、size=100 → offset=2147483700 > Integer.MAX_VALUE，而 JDBC 的
        // setFirstResult 只接受 int。Spring Data 会在 PageableUtils 里抛
        // InvalidDataAccessApiUsageException，用户拿到 500 —— 「翻到太后面」是正常操作，
        // 不该是服务器错误。
        //
        // 旧实现（全量加载后手工 subList）用 long 算起点并在越界时直接返回空列表，
        // 天然不会踩到；改成 SQL 分页后这个保护必须显式补回来。这里用 long 算偏移是
        // 关键：以 int 相乘会溢出成负数，判断随之失效。
        //
        // 注意不能直接 return 空页了事：total 必须是**真实总数**，前端要靠它渲染
        // 分页器。所以这里只跳过内容查询、仍然走一次 count。
        long offset = (long) query.page() * query.size();
        if (offset > Integer.MAX_VALUE) {
            long total = taskRepository.count(filter);
            return PageDto.ofPage(List.of(), query.page(), query.size(), total);
        }

        PageRequest pageRequest = PageRequest.of(query.page(), query.size());
        // 排序不能走 PageRequest 的 Sort：前两个键都需要 CASE/NULL 处理，Sort 只能表达
        // 「列名 + 方向」。所以把 orderBy 写进 Specification 本身（且必须与 count 用的
        // 那个分开——count 查询带上 ORDER BY 会白排序，某些数据库还会直接报错）。
        Page<TaskEntity> page = taskRepository.findAll(withDisplayOrder(filter), pageRequest);

        Instant now = Instant.now();
        List<TaskDto> items = page.getContent().stream()
                .map(task -> toDto(task, now))
                .toList();
        return PageDto.ofPage(items, query.page(), query.size(), page.getTotalElements());
    }

    /**
     * 给筛选条件套上展示顺序（SQL 层 ORDER BY）。
     *
     * <h2>为什么不用 {@code Sort}</h2>
     * 前两个排序键都不是「列 + 方向」能表达的：
     * <ul>
     *   <li><b>未完成优先</b>：status 是 varchar，字母序是 {@code DOING < DONE < TODO}，
     *       直接排会把已完成的放到待办前面 —— 与界面完全相反。必须 CASE 映射成 0/1。</li>
     *   <li><b>无截止时间排最后</b>：用 {@code CASE WHEN due_at IS NULL THEN 1 ELSE 0 END}
     *       显式表达，而不是依赖 PostgreSQL「ASC 默认 NULLS LAST」。后者虽然当前成立，
     *       但那是方言行为，换库或换排序方向就静默翻转，而这种错只在少数行上体现，
     *       肉眼极难发现。</li>
     * </ul>
     *
     * <h2>为什么要 created_at 与 id 两级兜底</h2>
     * 前两个键都可能并列。并列时数据库返回的先后是<b>未定义</b>的，翻页就可能重复或
     * 漏项（同一行在第 1 页出现两次、另一行谁也没出现）。created_at 在同一毫秒批量
     * 导入时也不唯一，最终用主键 id 兜底才能保证全序。
     */
    private static Specification<TaskEntity> withDisplayOrder(Specification<TaskEntity> filter) {        return (root, query, builder) -> {
            // 只为 count 查询服务时不需要排序（Spring Data 会传入 count 的 CriteriaQuery，
            // 其 select 是 count，此时加 orderBy 会报「ORDER BY 表达式不在 GROUP BY 中」）
            if (query.getResultType() == Long.class || query.getResultType() == long.class) {
                return filter.toPredicate(root, query, builder);
            }

            Expression<Integer> unfinishedFirst = builder.<Integer>selectCase()
                    .when(builder.equal(root.get("status"), TaskStatus.DONE), 1)
                    .otherwise(0);
            Expression<Integer> dueDateMissing = builder.<Integer>selectCase()
                    .when(builder.isNull(root.get("dueAt")), 1)
                    .otherwise(0);
            query.orderBy(
                    builder.asc(unfinishedFirst),
                    builder.asc(dueDateMissing),
                    builder.asc(root.get("dueAt")),
                    builder.desc(root.get("createdAt")),
                    builder.asc(root.get("id")));
            return filter.toPredicate(root, query, builder);
        };
    }

    /**
     * 全部任务，顺序与界面一致（导出用，阶段 12）。
     *
     * <p>不走 {@link #search} 是因为它受分页上限约束（{@code MAX_SIZE=100}），
     * 拿它做导出会在任务超过 100 条时**静默少导**。这里显式返回全量，但**复用同一段
     * SQL 排序**（{@link #withDisplayOrder}），所以导出文件里的顺序与界面逐行一致 ——
     * 而且是同一处真相，不存在「两套顺序慢慢漂移」的可能。
     */
    @Transactional(readOnly = true)
    public List<TaskDto> listAll() {
        Instant now = Instant.now();
        return taskRepository.findAll(withDisplayOrder(noFilter())).stream()
                .map(task -> toDto(task, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskDto get(UUID id) {
        return toDto(requireTask(id), Instant.now());
    }

    @Transactional(readOnly = true)
    public List<TaskDto> findByIds(List<UUID> ids) {
        if (ids.isEmpty()) {
            // 空列表若照常拼 `id IN ()`，生成的是非法 SQL（PostgreSQL 直接报语法错）。
            // 原 findAllById 对空列表返回空，行为要保持一致。
            return List.of();
        }
        Instant now = Instant.now();
        // 同样走 SQL 排序：这几个方法的结果会与列表页并排展示，顺序不一致会显得「跳」
        return taskRepository.findAll(withDisplayOrder((root, query, builder) -> root.get("id").in(ids)))
                .stream()
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

    /**
     * 标记完成 / 取消完成。
     *
     * <p>取消完成时回到<b>完成前的状态</b>而不是一律 {@code TODO}：
     * 把进行中的任务误标完成再取消，原实现会静默降级成待办。
     * 目标状态由 {@link TaskEntity#reopenTarget()} 决定，必须在 {@code setStatus}
     * <b>之前</b>取 —— 后者会在离开 DONE 时清空 {@code previousStatus}。
     */
    @Transactional
    public TaskDto setCompleted(UUID id, boolean completed) {
        TaskEntity task = requireTask(id);
        Instant now = Instant.now();
        TaskStatus target = completed ? TaskStatus.DONE : task.reopenTarget();
        task.setStatus(target, now);
        task.touch(now);
        log.info("任务完成状态变更 id={} completed={} 目标状态={}", id, completed, target);
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
        // 这里的顺序是「按截止时间升序」，与列表页的展示顺序（未完成优先）不同 ——
        // 日历/提醒场景关心的是时间轴，已完成的本来就被排除了。所以不复用
        // withDisplayOrder，单独用 Sort 表达即可（只需一列，无 CASE/NULL 需求）。
        Specification<TaskEntity> specification = (root, query, builder) -> builder.and(
                builder.isNotNull(root.get("dueAt")),
                builder.greaterThanOrEqualTo(root.get("dueAt"), from),
                builder.lessThanOrEqualTo(root.get("dueAt"), to),
                builder.notEqual(root.get("status"), TaskStatus.DONE));

        Instant now = Instant.now();
        return taskRepository.findAll(specification, Sort.by(Sort.Direction.ASC, "dueAt")).stream()
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

    /**
     * 空筛选条件（不限制任何行）。
     *
     * <p>单独抽出来是为了让调用处读起来是「全量 + 展示顺序」，而不是造一个
     * 永远返回 true 的 lambda 混在业务代码里。
     */
    private static Specification<TaskEntity> noFilter() {
        return (root, query, builder) -> builder.conjunction();
    }

    private Specification<TaskEntity> buildSpecification(TaskQuery query) {
        return (root, criteriaQuery, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.status() != null) {
                predicates.add(builder.equal(root.get("status"), query.status()));
            }
            if (StringUtils.hasText(query.keyword())) {
                // 与全局搜索同一套口径：小写化 + 转义通配符。内联拼接会让搜 "100%"
                // 命中「100 之后任意字符」、搜 "_" 命中全库。
                String pattern = SearchPatterns.likePattern(query.keyword());
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("title")), pattern, SearchPatterns.ESCAPE),
                        builder.like(builder.lower(builder.coalesce(root.get("description"), "")),
                                pattern, SearchPatterns.ESCAPE)));
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

    /**
     * 全量统计（审查 #16）：未完成数与逾期数。
     *
     * <p>首页统计卡用。与 {@link #search} 的分页结果无关 —— 后者最多返回一页
     * （默认 20 条），用它派生统计等于给用户看一个「最多 20」的假数字。
     *
     * <p>{@code now} 用 {@link Instant#now()} 而非注入 Clock：本服务其余方法的逾期
     * 判定也直接用 {@code Instant.now()}（见 {@link #toDto}），口径必须一致；
     * 引入 Clock 只改这一个方法会造成「统计的逾期」与「列表的逾期」用两个时间源。
     */
    @Transactional(readOnly = true)
    public TaskStatsDto stats() {
        TaskRepository.TaskStatsRow row = taskRepository.countStats(Instant.now());
        return new TaskStatsDto(row.getPending(), row.getOverdue());
    }
}
