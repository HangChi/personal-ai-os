package com.personalos.calendar.webhook;

import com.personalos.common.config.RuntimeRole;
import com.personalos.common.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The operator configures the destination. Deliveries retain a stable id across retries. */
@Component
@ConditionalOnProperty(name="app.reminders.webhook.enabled",havingValue="true")
public class ReminderWebhookWorker {
    private final JdbcTemplate db;
    private final RuntimeRole role;
    private final JsonCodec json;
    private final MeterRegistry meters;
    private final URI endpoint;
    private final String target;
    private final byte[] secret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("reminder-webhook").factory());
    private final AtomicBoolean busy = new AtomicBoolean();

    public ReminderWebhookWorker(JdbcTemplate db,RuntimeRole role,JsonCodec json,MeterRegistry meters,
            @Value("${app.reminders.webhook.url:}") String url,
            @Value("${app.reminders.webhook.secret:}") String secret,
            @Value("${app.reminders.webhook.allow-local-http:false}") boolean allowLocalHttp) {
        this.db=db; this.role=role; this.json=json; this.meters=meters;
        endpoint=URI.create(url);
        boolean local=allowLocalHttp && "http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost());
        if ((!local && !"https".equals(endpoint.getScheme())) || endpoint.getHost()==null
                || endpoint.getUserInfo()!=null || endpoint.getFragment()!=null || secret.length()<32) {
            throw new IllegalArgumentException("Invalid reminder webhook configuration");
        }
        this.secret=secret.getBytes(StandardCharsets.UTF_8);
        target=JsonCodec.sha256Hex(endpoint.toASCIIString());
    }

    @Scheduled(fixedDelayString="${app.reminders.webhook.poll-ms:5000}")
    public void dispatch() {
        if (!role.allows("delivery") || !busy.compareAndSet(false,true)) return;
        try { executor.execute(() -> { try { deliver(); } finally { busy.set(false); } }); }
        catch (RejectedExecutionException ex) { busy.set(false); }
    }

    public void deliver() {
        db.update("INSERT INTO reminder_webhook_target(target) VALUES (?) ON CONFLICT DO NOTHING",target);
        db.update("""
                INSERT INTO reminder_webhook_delivery(target,reminder_id)
                SELECT t.target,r.id FROM reminder_webhook_target t CROSS JOIN reminder r
                WHERE t.target=? AND r.status='PENDING' AND r.remind_at<=now()
                    AND r.remind_at>=t.enabled_at AND r.remind_at>now()-interval '24 hours'
                    AND NOT EXISTS (SELECT 1 FROM reminder_webhook_delivery d
                        WHERE d.target=t.target AND d.reminder_id=r.id)
                ORDER BY r.remind_at,r.id LIMIT 100 ON CONFLICT DO NOTHING
                """,target);
        db.update("UPDATE reminder_webhook_delivery SET state='FAILED' WHERE target=? AND state='SENDING' "
                +"AND attempts>=5 AND next_attempt_at<=now()",target);
        db.update("DELETE FROM reminder_webhook_delivery WHERE created_at<now()-interval '30 days'");
        for (int i=0; i<10 && !Thread.currentThread().isInterrupted(); i++) {
            var rows=db.queryForList("""
                    WITH ready AS (
                        SELECT d.id FROM reminder_webhook_delivery d JOIN reminder r ON r.id=d.reminder_id
                        WHERE d.target=? AND d.state IN ('PENDING','SENDING') AND d.next_attempt_at<=now()
                            AND d.attempts<5 AND r.status='PENDING' AND r.remind_at>now()-interval '24 hours'
                        ORDER BY d.next_attempt_at,d.id FOR UPDATE OF d SKIP LOCKED LIMIT 1
                    ), claimed AS (
                        UPDATE reminder_webhook_delivery d SET state='SENDING',attempts=attempts+1,
                            next_attempt_at=now()+interval '60 seconds' FROM ready WHERE d.id=ready.id RETURNING d.*
                    )
                    SELECT d.id,d.attempts,r.id AS reminder_id,r.source_type,r.title,r.remind_at
                    FROM claimed d JOIN reminder r ON r.id=d.reminder_id
                    """,target);
            if (rows.isEmpty()) return;
            send(rows.getFirst());
        }
    }

    private void send(Map<String,Object> row) {
        UUID id=(UUID)row.get("id");
        int attempts=((Number)row.get("attempts")).intValue();
        String state="PENDING";
        try {
            String body=json.toJson(Map.of("event","reminder.due","id",id,
                    "reminderId",row.get("reminder_id"),"title",row.get("title"),
                    "sourceType",row.get("source_type"),"remindAt",row.get("remind_at").toString()));
            String timestamp=Long.toString(Instant.now().getEpochSecond());
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                    .header("Content-Type","application/json")
                    .header("Idempotency-Key",id.toString())
                    .header("X-PersonalOS-Timestamp",timestamp)
                    .header("X-PersonalOS-Signature","sha256="+signature(timestamp,body,secret))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try (var ignored=response.body()) {
                int code=response.statusCode();
                if (code>=200 && code<300) state="SENT";
                else if (code!=429 && code<500) state="FAILED";
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception ex) {
            // The destination and body can contain private information.
        }
        if (attempts>=5 && state.equals("PENDING")) state="FAILED";
        db.update("UPDATE reminder_webhook_delivery SET state=?,next_attempt_at=now()+?*interval '1 second' "
                +"WHERE id=? AND attempts=? AND state='SENDING'",state,30 << attempts,id,attempts);
        meters.counter("personalos.reminders.webhook.deliveries","result",state.toLowerCase(java.util.Locale.ROOT)).increment();
    }

    static String signature(String timestamp,String body,byte[] secret) throws java.security.GeneralSecurityException {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret,"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((timestamp+"."+body).getBytes(StandardCharsets.UTF_8)));
    }

    @PreDestroy public void close() { executor.shutdownNow(); }
}
