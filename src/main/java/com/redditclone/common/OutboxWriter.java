package com.redditclone.common;

import com.redditclone.common.correlation.CorrelationIdFilter;
import org.slf4j.MDC;
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
    // correlation_id is captured from MDC at write time (the HTTP/STOMP thread that produced this event) —
    // by the time a worker drains this row later on its own thread, the original request's MDC is long
    // gone, so it must be persisted with the row to survive that hop. See CorrelationIdFilter.
    public void writeEvent(String eventType, Object payload) {
        jdbc.update("INSERT INTO outbox_events (id, event_type, payload, correlation_id) VALUES (?, ?, ?::jsonb, ?)",
                ids.nextId(), eventType, json.writeValueAsString(payload), MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    // Batched counterpart to writeEvent, for a caller that already has several same-type events ready at
    // once (e.g. several distinct u/{username} mentions in one comment) — one round trip instead of one
    // insert per event. All events in one call share the same correlation id (they're all produced by the
    // same request).
    public void writeEvents(String eventType, List<?> payloads) {
        if (payloads.isEmpty()) {
            return;
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        List<Object[]> rows = payloads.stream()
                .map(payload -> new Object[]{ids.nextId(), eventType, json.writeValueAsString(payload), correlationId})
                .toList();
        jdbc.batchUpdate("INSERT INTO outbox_events (id, event_type, payload, correlation_id) VALUES (?, ?, ?::jsonb, ?)", rows);
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
