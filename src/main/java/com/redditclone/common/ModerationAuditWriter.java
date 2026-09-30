package com.redditclone.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Single writer for the moderation_actions/mod_queue tables — genuinely shared infrastructure fed from
// both `community` (bans, automod) and `moderation` (human mod actions, reports). Lives in `common`,
// not `moderation`, so `community` can depend on it without recreating the community<->moderation cycle
// ModuleBoundaryTest forbids (raw SQL, not a JPA repository, for the same reason OutboxWorker owns
// outbox_events this way rather than through a cross-module repository).
@Component
public class ModerationAuditWriter {

    private final JdbcTemplate jdbc;
    private final UuidV7Generator ids;

    public ModerationAuditWriter(JdbcTemplate jdbc, UuidV7Generator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    public void logAction(UUID communityId, UUID actorId, String action, String targetType, UUID targetId, String reason) {
        jdbc.update("""
                INSERT INTO moderation_actions (id, community_id, actor_id, action, target_type, target_id, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, ids.nextId(), communityId, actorId, action, targetType, targetId, reason);
    }

    public void upsertModQueue(UUID communityId, String targetType, UUID targetId) {
        jdbc.update("""
                INSERT INTO mod_queue (community_id, target_type, target_id, report_count, first_reported_at)
                VALUES (?, ?, ?, 1, now())
                ON CONFLICT (community_id, target_type, target_id) DO UPDATE SET report_count = mod_queue.report_count + 1
                """, communityId, targetType, targetId);
    }
}
