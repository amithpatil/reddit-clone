package com.redditclone.notify;

import com.redditclone.common.OutboxWriter;
import com.redditclone.common.UuidV7Generator;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Owns the "notification" slice of outbox_events — split out from vote.OutboxWorker (which excludes
// event_type='notification' from its own claim query) so the notify module, which already owns the
// notifications table's schema, is what actually writes to it. Matches the "each concern gets its own
// dedicated worker" pattern this codebase already uses for media (ImageProcessingWorker/
// VideoProcessingWorker), rather than bolting notification handling onto a worker named for a different
// concern. Raw JDBC against outbox_events, not a cross-module repository, for the same shared-
// infrastructure-table reasoning ModerationAuditWriter/OutboxWriter already establish elsewhere.
@Component
public class NotificationOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxWorker.class);
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final UuidV7Generator ids;
    private final ObjectMapper json;
    private final OutboxWriter outboxWriter;

    public NotificationOutboxWorker(JdbcTemplate jdbc, UuidV7Generator ids, ObjectMapper json, OutboxWriter outboxWriter) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.json = json;
        this.outboxWriter = outboxWriter;
    }

    @Scheduled(fixedDelay = 2000)
    @SchedulerLock(name = "notificationOutboxWorker", lockAtLeastFor = "1s", lockAtMostFor = "30s")
    @Transactional
    public void processBatch() {
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT id, payload FROM outbox_events
                WHERE processed_at IS NULL AND event_type = 'notification'
                ORDER BY id
                LIMIT %d
                FOR UPDATE SKIP LOCKED
                """.formatted(BATCH_SIZE));
        if (events.isEmpty()) {
            return;
        }

        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> event : events) {
            try {
                JsonNode payload = json.readTree(event.get("payload").toString());
                rows.add(new Object[]{
                        ids.nextId(),
                        UUID.fromString(payload.path("userId").asString()),
                        payload.path("type").asString(),
                        json.writeValueAsString(payload.path("source"))
                });
            } catch (Exception e) {
                log.warn("outbox event {} could not be applied, marking processed without applying it: {}", event.get("id"), e.getMessage());
            }
        }

        // Isolated in its own transaction (see OutboxWriter.insertNotifications) so one bad row (e.g. a
        // stale user_id violating notifications_user_id_fkey) can't poison this tick's outbox_events
        // processed_at marking below for every other, otherwise-valid event in the batch.
        try {
            outboxWriter.insertNotifications(rows);
        } catch (Exception e) {
            log.warn("failed to insert {} notification row(s) from this batch: {}", rows.size(), e.getMessage());
        }

        jdbc.batchUpdate("UPDATE outbox_events SET processed_at = now() WHERE id = ?",
                events.stream().map(e -> new Object[]{e.get("id")}).toList());
    }
}
