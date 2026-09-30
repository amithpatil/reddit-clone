package com.redditclone.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

// Shared writer for outbox_events — same reasoning as ModerationAuditWriter: outbox_events is genuinely
// shared infrastructure fed from multiple modules (vote.VoteService and comment.CommentService both write
// it, and comment/vote have no dependency relationship to route a direct call through), so a shared common
// writer avoids recreating a cross-module cycle. Raw JDBC, not a JPA repository, for the same reason.
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final UuidV7Generator ids;
    private final ObjectMapper json;

    public OutboxWriter(JdbcTemplate jdbc, UuidV7Generator ids, ObjectMapper json) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.json = json;
    }

    // payload is Object, not Map<String,Object>, so this also accepts a typed record (e.g. vote's
    // VoteEventPayload) — Jackson serializes a record's components the same way it does a map's entries.
    public void writeEvent(String eventType, Object payload) {
        jdbc.update("INSERT INTO outbox_events (id, event_type, payload) VALUES (?, ?, ?::jsonb)",
                ids.nextId(), eventType, json.writeValueAsString(payload));
    }

    // Batched counterpart to writeEvent, for a caller that already has several same-type events ready at
    // once (e.g. several distinct u/{username} mentions in one comment) — one round trip instead of one
    // insert per event.
    public void writeEvents(String eventType, List<?> payloads) {
        if (payloads.isEmpty()) {
            return;
        }
        List<Object[]> rows = payloads.stream()
                .map(payload -> new Object[]{ids.nextId(), eventType, json.writeValueAsString(payload)})
                .toList();
        jdbc.batchUpdate("INSERT INTO outbox_events (id, event_type, payload) VALUES (?, ?, ?::jsonb)", rows);
    }

    // Runs in its own transaction (REQUIRES_NEW), separate from whatever transaction the caller is
    // already in (notify.NotificationOutboxWorker's own batch-processing tick) — Postgres aborts an
    // entire transaction on any statement failure, so without this, one bad notification row (e.g. a
    // stale/foreign-key-violating user_id) would poison that whole tick's other, already-computed work
    // too, not just the notification insert itself.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertNotifications(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO notifications (id, user_id, type, source) VALUES (?, ?, ?, ?::jsonb)", rows);
    }
}
