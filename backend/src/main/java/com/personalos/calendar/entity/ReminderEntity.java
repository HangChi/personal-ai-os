package com.personalos.calendar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 提醒（物化记录）。
 *
 * <h2>为什么要物化，而不是查询时现算</h2>
 * 「已读」是无法从日程本身推导出来的状态。现算意味着每条提醒都是临时的，读完就忘了读没读；
 * 物化之后才能承载已读状态与审计痕迹，也才能把「同一发生时刻只有一条」这条约束
 * 落到数据库的唯一键上（而不是靠代码里的小心）。
 *
 * <p>唯一键 {@code (sourceType, sourceId, occurrenceAt)} 是幂等的基础。
 */
@Entity
@Table(name = "reminder")
public class ReminderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 20, nullable = false)
    private ReminderSource sourceType;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    /** 标题快照：来源被改名或删除后，仍能说明当时提醒的是什么 */
    @Column(name = "title", length = 300, nullable = false)
    private String title;

    /** 发生时刻（日程的该次开始时间 / 任务的截止时间） */
    @Column(name = "occurrence_at", nullable = false)
    private Instant occurrenceAt;

    /** 提醒时刻 = 发生时刻 − 提前量 */
    @Column(name = "remind_at", nullable = false)
    private Instant remindAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ReminderStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    /** 供 JPA 使用 */
    protected ReminderEntity() {
    }

    public ReminderEntity(ReminderSource sourceType, UUID sourceId, String title,
                          Instant occurrenceAt, Instant remindAt, Instant now) {
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.title = title;
        this.occurrenceAt = occurrenceAt;
        this.remindAt = remindAt;
        this.status = ReminderStatus.PENDING;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public ReminderSource getSourceType() {
        return sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public String getTitle() {
        return title;
    }

    public Instant getOccurrenceAt() {
        return occurrenceAt;
    }

    public Instant getRemindAt() {
        return remindAt;
    }

    public ReminderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    /**
     * 标记已读。
     *
     * <p>幂等：已经读过时不改动 {@code readAt}，保留首次阅读的时间（那才是有意义的时刻）。
     */
    public boolean markRead(Instant now) {
        if (status == ReminderStatus.READ) {
            return false;
        }
        this.status = ReminderStatus.READ;
        this.readAt = now;
        return true;
    }
}
