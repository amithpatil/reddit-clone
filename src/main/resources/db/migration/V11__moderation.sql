-- Phase 3: moderation (reports/mod_queue, automod, bans, modmail mutes) and the account-level admin ban.
-- reports/mod_queue/moderation_actions/bans already exist from the original Phase 1 schema (V5) — this
-- only adds what Phase 1 deliberately deferred.

ALTER TABLE users ADD COLUMN is_site_admin BOOLEAN NOT NULL DEFAULT false;

-- Deliberately separate from communities.rules (human-readable sidebar text, already in use) — this is
-- machine-evaluated config. Actions capped at remove/report: an automatic ban is a human-only decision.
CREATE TABLE automod_rules (
  id            UUID PRIMARY KEY,
  community_id  UUID NOT NULL REFERENCES communities(id),
  rule_type     TEXT NOT NULL CHECK (rule_type IN ('keyword','regex','karma_threshold')),
  config        JSONB NOT NULL,
  action        TEXT NOT NULL CHECK (action IN ('remove','report')),
  enabled       BOOLEAN NOT NULL DEFAULT true,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX automod_rules_community_idx ON automod_rules (community_id) WHERE enabled;

-- Deliberately flat, no threading/replies — a one-way "contact the mods" channel, just enough for mute
-- to restrict something real. Full messaging is Phase 4 territory.
CREATE TABLE mod_mail_messages (
  id            UUID PRIMARY KEY,
  community_id  UUID NOT NULL REFERENCES communities(id),
  sender_id     UUID NOT NULL REFERENCES users(id),
  body          TEXT NOT NULL CHECK (char_length(body) <= 10000),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX mod_mail_messages_community_idx ON mod_mail_messages (community_id, created_at DESC);

-- Same permanent-vs-temporary-by-expiry convention as bans, but a separate mechanism: mute only blocks
-- modmail, never posting/commenting (that's what bans.expires_at already covers).
CREATE TABLE mod_mail_mutes (
  community_id  UUID NOT NULL REFERENCES communities(id),
  user_id       UUID NOT NULL REFERENCES users(id),
  muted_by      UUID NOT NULL REFERENCES users(id),
  reason        TEXT,
  expires_at    TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (community_id, user_id)
);

-- Account-level (site-wide) actions, separate from moderation_actions: an account ban isn't scoped to
-- any one community, so it doesn't fit that table's NOT NULL community_id FK.
CREATE TABLE account_actions (
  id               UUID PRIMARY KEY,
  actor_id         UUID NOT NULL REFERENCES users(id),
  target_user_id   UUID NOT NULL REFERENCES users(id),
  action           TEXT NOT NULL,
  reason           TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX account_actions_target_idx ON account_actions (target_user_id, created_at DESC);

-- reports/moderation_actions were declared PARTITION BY RANGE (created_at) in the original Phase 1
-- schema, but no actual partitions were ever created — verified live before writing this migration that
-- a partitioned table with zero partitions rejects every insert ("no partition of relation found for
-- row"). This phase is the first code that actually writes to either table, so it's the first place this
-- surfaces. Creates one partition per month, a rolling 24-month window from the current month.
-- A real deployment needs an automated job (pg_partman, or a scheduled task in the style of
-- RankDecayJob) to keep creating future months ahead of time — out of scope here, flagged so it isn't
-- forgotten. IF NOT EXISTS makes this safe to re-run.
DO $$
DECLARE
  month_start date := date_trunc('month', now());
  month_end date;
  i int;
BEGIN
  FOR i IN 0..23 LOOP
    month_end := month_start + INTERVAL '1 month';
    EXECUTE format('CREATE TABLE IF NOT EXISTS reports_%s PARTITION OF reports FOR VALUES FROM (%L) TO (%L)',
                    to_char(month_start, 'YYYY_MM'), month_start, month_end);
    EXECUTE format('CREATE TABLE IF NOT EXISTS moderation_actions_%s PARTITION OF moderation_actions FOR VALUES FROM (%L) TO (%L)',
                    to_char(month_start, 'YYYY_MM'), month_start, month_end);
    month_start := month_end;
  END LOOP;
END $$;
