package com.personalos.calendar.repository;

import com.personalos.calendar.entity.ReminderEntity;
import com.personalos.calendar.entity.ReminderSource;
import com.personalos.calendar.entity.ReminderStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 提醒仓储。
 */
public interface ReminderRepository extends JpaRepository<ReminderEntity, UUID> {

    List<ReminderEntity> findByStatusAndRemindAtLessThanEqualOrderByRemindAtAsc(
            ReminderStatus status, java.time.Instant remindAt, Pageable pageable);

    List<ReminderEntity> findByStatusOrderByRemindAtAsc(ReminderStatus status, Pageable pageable);

    long countBySourceTypeAndSourceId(ReminderSource sourceType, UUID sourceId);

    long countByStatus(ReminderStatus status);

    /**
     * 「插入并忽略冲突」—— 扫描物化的核心。
     *
     * <p><b>为什么用原生 UPSERT 而不是「先查再插」</b>：后者在并发下必然出现竞态
     * （两个线程都查到"不存在"，然后都插入）。虽然本阶段只有一个调度线程，
     * 但「手动触发扫描 + 定时扫描」同时发生是完全可能的。
     * 把唯一性交给数据库的唯一约束，是唯一可靠的做法。
     *
     * <p><b>为什么参数用 {@link OffsetDateTime} 而不是 {@link java.time.Instant}</b>：
     * 原生 SQL 里 Instant 会被绑成 {@code TIMESTAMP}（无时区），
     * PostgreSQL 随后会按会话时区把它隐式转成 {@code TIMESTAMPTZ} —— 值可能被平移，
     * 而且**不会报错**。用 OffsetDateTime 才会被正确绑成 {@code TIMESTAMPTZ}。
     *
     * <p>id 与 created_at 交给数据库默认值，不在此处生成。
     *
     * @return 1 表示确实插入了；0 表示该「来源 + 发生时刻」已存在（被忽略）
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO reminder (source_type, source_id, title, occurrence_at, remind_at)
            VALUES (:sourceType, :sourceId, :title, :occurrenceAt, :remindAt)
            ON CONFLICT (source_type, source_id, occurrence_at) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("sourceType") String sourceType,
                       @Param("sourceId") UUID sourceId,
                       @Param("title") String title,
                       @Param("occurrenceAt") OffsetDateTime occurrenceAt,
                       @Param("remindAt") OffsetDateTime remindAt);

    /**
     * 删除某个来源的**待提醒**记录（保留已读/已取消的历史）。
     *
     * <p>为什么要有它：提醒是「标题 + 发生时刻 + 提前量」的快照。来源被修改后，
     * 旧快照不会自己变 —— 改了时间会照常弹旧时间，改了标题会弹旧标题，
     * 改了提前量则因为 UPSERT 是 {@code DO NOTHING} 而完全不生效。
     * 修改路径调用本方法清掉 PENDING，下一轮扫描就会按新规则重建。
     *
     * <p><b>刻意只删 PENDING</b>：已读记录是「确实提醒过」的历史事实。连它一起删掉，
     * 同一发生时刻会被重新物化 again，用户会收到一条本该已经读过的提醒。
     *
     * @return 删除的行数
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from ReminderEntity r where r.sourceType = :sourceType"
            + " and r.sourceId = :sourceId and r.status = :status")
    int deleteBySourceAndStatus(@Param("sourceType") ReminderSource sourceType,
                                @Param("sourceId") UUID sourceId,
                                @Param("status") ReminderStatus status);

    /**
     * 删除某个来源的全部提醒。
     *
     * <p><b>为什么是删除而不是标记取消</b>：提醒唯一的作用就是指向它的来源。
     * 来源被删掉之后，那行记录连同标题快照都只是噪音，留着既不会展示、也无法再被操作。
     * 整行删除让「来源删除后不留任何提醒」成为一条可以用一条 SQL 断言的不变量。
     *
     * @return 实际删除的行数
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from ReminderEntity r where r.sourceType = :sourceType and r.sourceId = :sourceId")
    int deleteBySource(@Param("sourceType") ReminderSource sourceType,
                       @Param("sourceId") UUID sourceId);

    /** 清理已完成、清除截止时间或改期任务的待提醒，保留已读历史。 */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM reminder r
            WHERE r.source_type = 'TASK' AND r.status = 'PENDING'
              AND NOT EXISTS (
                  SELECT 1 FROM task t WHERE t.id = r.source_id
                    AND t.status <> 'DONE' AND t.due_at = r.occurrence_at
              )
            """, nativeQuery = true)
    int pruneInvalidTaskReminders();

    /** 标题只更新快照，不删除重建，以免遗漏扫描回看窗口之外的逾期提醒。 */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE reminder r SET title = t.title
            FROM task t
            WHERE r.source_type = 'TASK' AND r.status = 'PENDING'
              AND r.source_id = t.id AND r.title IS DISTINCT FROM t.title
            """, nativeQuery = true)
    int refreshTaskTitles();

    /**
     * 清理「来源已不存在」的提醒 —— 自愈式兜底。
     *
     * <h2>为什么需要它</h2>
     * 日程删除时会顺手清掉自己的提醒，但**任务删除不会** —— {@code task} 模块并不知道
     * 提醒的存在，也不该知道（否则就为了这个功能引入一条反向依赖）。
     * 结果就是：删掉一个任务后，它的提醒仍然躺在表里，到点后照样弹给用户 ——
     * 一条指向「已不存在的任务」的幽灵提醒。
     *
     * <p>与其在每条删除路径上都挂一个清理钩子（漏掉一条就静默出问题），
     * 不如让扫描顺手把孤儿提醒扫掉：任何删除路径、甚至直接改数据库，都会在下一轮被纠正。
     *
     * <p>只处理 {@code remind_at <= upTo} 的行：还没到点的提醒即使来源已删也不会被用户看到，
     * 留到它们进入时间窗口时再处理，避免每轮扫描都全表检查。
     *
     * @param upTo 处理到哪个提醒时刻为止（传扫描窗口终点）
     * @return 清理掉的条数
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM reminder r
            WHERE r.remind_at <= :upTo
              AND (
                    (r.source_type = 'CALENDAR_EVENT'
                        AND NOT EXISTS (SELECT 1 FROM calendar_event e WHERE e.id = r.source_id))
                 OR (r.source_type = 'TASK'
                        AND NOT EXISTS (SELECT 1 FROM task t WHERE t.id = r.source_id))
              )
            """, nativeQuery = true)
    int pruneOrphans(@Param("upTo") OffsetDateTime upTo);
}
