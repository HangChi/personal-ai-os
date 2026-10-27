package com.personalos.task.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 个人任务。
 *
 * <p><b>不变量</b>：{@code completedAt} 仅当 {@code status == DONE} 时非空。
 * 由 {@link #setStatus} 统一维护，避免出现「已完成但没有完成时间」或
 * 「未完成却有完成时间」这两种会污染统计的脏数据。
 */
@Entity
@Table(name = "task")
public class TaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 10, nullable = false)
    private TaskPriority priority;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * 进入 {@code DONE} 之前的非完成状态（{@code TODO} 或 {@code DOING}）。
     *
     * <p>存在的唯一理由：{@code reopen} 要能区分「原来是待办」与「原来是进行中」。
     * 没有它的话，把进行中的任务误标完成后取消，任务就永久退回待办 ——
     * 状态只有三个值，这个信息在别处无处可恢复。
     *
     * <p>只在「进入 DONE」时写入，离开 DONE 时清空。历史数据为 {@code null}，
     * 语义等价于「不知道原来是什么」，按 TODO 处理。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 20)
    private TaskStatus previousStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 标签（0–5 个，自由文本，阶段 14）。
     *
     * <p>用 {@code List} + {@code @OrderColumn} 而不是 {@code Set}：标签顺序在列表、
     * 编辑回填与导出三处都可见，{@code Set} 持久化后顺序不保证，
     * 用户会看到「同样的数据，刷新后标签换了个位置」。
     *
     * <p>归一化（去空白/去空/去重/校验上限）由 {@code TaskTags} 负责，实体不重复校验。
     */
    @ElementCollection
    @CollectionTable(name = "task_tag", joinColumns = @JoinColumn(name = "task_id"))
    @OrderColumn(name = "tag_index")
    @Column(name = "tag", length = 20, nullable = false)
    private List<String> tags = new ArrayList<>();

    /** 供 JPA 使用 */
    protected TaskEntity() {
    }

    public TaskEntity(String title, String description, TaskPriority priority, Instant dueAt, Instant now) {
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.dueAt = dueAt;
        this.status = TaskStatus.TODO;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setPriority(TaskPriority priority) {
        this.priority = priority;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    /**
     * 状态迁移；同步维护 {@code completedAt} 不变量与「完成前状态」。
     *
     * <h2>为什么在这里记 previousStatus 而不是在 Service 里</h2>
     * 进入 DONE 的入口不止一个：REST 的 {@code /complete}、更新请求里直接传
     * {@code status=DONE}、以及 Agent 工具。记在实体的状态迁移里，三条路径自动一致；
     * 记在 Service 里就得每处都记得调，漏一处的表现是「重开后状态莫名其妙」。
     *
     * <h2>为什么只在进入 DONE 时写、离开时清</h2>
     * 这个字段只服务于「取消完成」这一个动作。离开 DONE 之后它就没有意义了，
     * 留着会让「再次完成 → 再次取消」的语义变得难以推理。
     *
     * <p>连续两次完成不会覆盖：只有从非 DONE 进入 DONE 才记录。
     * 否则 {@code DOING → DONE → DONE} 会把 previousStatus 刷成 DONE，reopen 就退不回去了。
     */
    public void setStatus(TaskStatus status, Instant now) {
        if (status == TaskStatus.DONE && this.status != TaskStatus.DONE) {
            this.previousStatus = this.status;
        }
        if (status != TaskStatus.DONE) {
            this.previousStatus = null;
        }
        this.status = status;
        this.completedAt = (status == TaskStatus.DONE) ? now : null;
    }

    /**
     * 取消完成时该回到的状态。
     *
     * @return 完成前的状态；从未记录过（历史数据或已是非完成态）时返回 {@code TODO}
     */
    public TaskStatus reopenTarget() {
        return previousStatus == null ? TaskStatus.TODO : previousStatus;
    }

    /** 返回副本：避免调用方直接改到持久化集合（Hibernate 用原集合跟踪脏检查） */
    public List<String> getTags() {
        return List.copyOf(tags);
    }

    /**
     * 整体替换标签。
     *
     * <p>刻意在原地清空再填充（而不是 `this.tags = new ArrayList<>(...)`）：
     * {@code @ElementCollection} 由 Hibernate 用原集合实例跟踪变更，
     * 换实例会让它丢失跟踪，表现为「改了标签但没落库」。
     */
    public void setTags(List<String> tags) {
        this.tags.clear();
        if (tags != null) {
            this.tags.addAll(tags);
        }
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }
}
