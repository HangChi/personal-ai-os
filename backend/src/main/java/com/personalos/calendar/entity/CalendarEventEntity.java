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
 * 日程事件（重复规则的**原始定义**，不是某一次发生）。
 *
 * <p>「发生实例」由 {@code RecurrenceCalculator} 按需展开，不落库 ——
 * 展开结果是从定义推导出来的，存下来只会带来「定义改了、实例没跟着改」的不一致。
 */
@Entity
@Table(name = "calendar_event")
public class CalendarEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "location", length = 200)
    private String location;

    @Column(name = "all_day", nullable = false)
    private Boolean allDay;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_type", length = 20, nullable = false)
    private RecurrenceType recurrenceType;

    /** 重复截止（**含**该时刻）；为空表示无限重复 */
    @Column(name = "recurrence_until")
    private Instant recurrenceUntil;

    /** 提前提醒量（分钟） */
    @Column(name = "reminder_lead_minutes", nullable = false)
    private Integer reminderLeadMinutes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 供 JPA 使用 */
    protected CalendarEventEntity() {
    }

    public CalendarEventEntity(String title, String description, String location, boolean allDay,
                               Instant startsAt, Instant endsAt, RecurrenceType recurrenceType,
                               Instant recurrenceUntil, int reminderLeadMinutes, Instant now) {
        this.title = title;
        this.description = description;
        this.location = location;
        this.allDay = allDay;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.recurrenceType = recurrenceType;
        this.recurrenceUntil = recurrenceUntil;
        this.reminderLeadMinutes = reminderLeadMinutes;
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

    public String getLocation() {
        return location;
    }

    public Boolean getAllDay() {
        return allDay;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public RecurrenceType getRecurrenceType() {
        return recurrenceType;
    }

    public Instant getRecurrenceUntil() {
        return recurrenceUntil;
    }

    public Integer getReminderLeadMinutes() {
        return reminderLeadMinutes;
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

    public void setLocation(String location) {
        this.location = location;
    }

    public void setAllDay(Boolean allDay) {
        this.allDay = allDay;
    }

    public void setStartsAt(Instant startsAt) {
        this.startsAt = startsAt;
    }

    public void setEndsAt(Instant endsAt) {
        this.endsAt = endsAt;
    }

    public void setRecurrenceType(RecurrenceType recurrenceType) {
        this.recurrenceType = recurrenceType;
    }

    public void setRecurrenceUntil(Instant recurrenceUntil) {
        this.recurrenceUntil = recurrenceUntil;
    }

    public void setReminderLeadMinutes(Integer reminderLeadMinutes) {
        this.reminderLeadMinutes = reminderLeadMinutes;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    /**
     * @return 单次发生结束后经过的毫秒数（用于把「开始时刻」映射为「结束时刻」）
     */
    public long durationMillis() {
        return endsAt.toEpochMilli() - startsAt.toEpochMilli();
    }
}
