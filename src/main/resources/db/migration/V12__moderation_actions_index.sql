-- moderation_actions had no index on community_id, unlike every sibling table added in V5/V11
-- (reports_open_idx, automod_rules_community_idx, mod_mail_messages_community_idx,
-- account_actions_target_idx) — every listModerationActions call forced a sequential scan across all
-- partitions. Creating it on the partitioned parent (no ONLY) makes Postgres create and attach a
-- matching index on every existing child partition automatically, and on every future one too.
CREATE INDEX moderation_actions_community_idx ON moderation_actions (community_id, created_at DESC);
