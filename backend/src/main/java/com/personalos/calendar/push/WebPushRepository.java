package com.personalos.calendar.push;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class WebPushRepository {
    private final JdbcTemplate jdbc;

    public WebPushRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public UUID subscribe(String endpoint, String key, String auth, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO web_push_subscription(id, endpoint, p256dh, auth, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(endpoint) DO UPDATE SET p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth
                RETURNING id
                """, UUID.class, UUID.randomUUID(), endpoint, key, auth, at(now));
    }

    public boolean exists(String endpoint) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM web_push_subscription WHERE endpoint = ?)", Boolean.class, endpoint));
    }

    public int count() {
        return jdbc.queryForObject("SELECT count(*) FROM web_push_subscription", Integer.class);
    }

    public void unsubscribe(String endpoint) {
        jdbc.update("DELETE FROM web_push_subscription WHERE endpoint = ?", endpoint);
    }

    /** 老提醒不补发；已读/取消/删除的提醒不会再进入队列。 */
    public void enqueue(Instant now) {
        jdbc.update("""
                INSERT INTO web_push_delivery(subscription_id, reminder_id, next_attempt_at, created_at)
                SELECT s.id, r.id, ?, ? FROM reminder r CROSS JOIN web_push_subscription s
                WHERE r.status = 'PENDING' AND r.remind_at <= ?
                  AND r.remind_at >= s.created_at AND r.remind_at > ?
                  AND NOT EXISTS (SELECT 1 FROM web_push_delivery d
                      WHERE d.subscription_id = s.id AND d.reminder_id = r.id)
                ORDER BY r.remind_at, r.id, s.id LIMIT 100
                ON CONFLICT DO NOTHING
                """, at(now), at(now), at(now), at(now.minusSeconds(86400)));
    }

    /** 原子领取，有界批次，租约允许进程重启后重试；HTTP 发送不占数据库事务。 */
    @Transactional
    public List<Delivery> claim(Instant now) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT d.subscription_id, d.reminder_id FROM web_push_delivery d
                    JOIN reminder r ON r.id = d.reminder_id
                    WHERE d.state IN ('PENDING', 'SENDING') AND d.next_attempt_at <= ?
                      AND d.attempts < 5 AND r.status = 'PENDING' AND r.remind_at > ?
                    ORDER BY d.next_attempt_at LIMIT 10 FOR UPDATE OF d SKIP LOCKED
                ), claimed AS (
                    UPDATE web_push_delivery d SET state = 'SENDING', attempts = attempts + 1,
                        next_attempt_at = ? FROM candidates c
                    WHERE d.subscription_id = c.subscription_id AND d.reminder_id = c.reminder_id
                    RETURNING d.*
                ) SELECT d.subscription_id, d.reminder_id, d.attempts, s.endpoint, s.p256dh, s.auth, r.title
                    FROM claimed d JOIN web_push_subscription s ON s.id = d.subscription_id
                    JOIN reminder r ON r.id = d.reminder_id
                """, (rs, row) -> new Delivery(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                at(now), at(now.minusSeconds(86400)), at(now.plusSeconds(180)));
    }

    public void finish(Delivery delivery, String state, Instant retryAt) {
        jdbc.update("""
                UPDATE web_push_delivery SET state = ?, next_attempt_at = ?
                WHERE subscription_id = ? AND reminder_id = ? AND attempts = ? AND state = 'SENDING'
                """, state, at(retryAt), delivery.subscriptionId(), delivery.reminderId(), delivery.attempts());
    }

    public void prune(Instant now) {
        jdbc.update("UPDATE web_push_delivery SET state = 'FAILED' WHERE state = 'SENDING' AND attempts >= 5 AND next_attempt_at <= ?", at(now));
        jdbc.update("DELETE FROM web_push_delivery WHERE created_at < ?", at(now.minusSeconds(30L * 86400)));
    }

    private OffsetDateTime at(Instant time) { return time.atOffset(ZoneOffset.UTC); }

    public record Delivery(UUID subscriptionId, UUID reminderId, int attempts,
                           String endpoint, String publicKey, String auth, String title) { }
}
