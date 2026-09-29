-- reports and moderation_actions are RANGE-partitioned by created_at. Postgres requires every unique/primary-key
-- constraint on a partitioned table to include the partition key column, so the primary key here is the composite
-- (id, created_at) rather than id alone (verified against a running Postgres 18 instance: a plain `id UUID PRIMARY KEY`
-- on a `PARTITION BY RANGE (created_at)` table is rejected with "unique constraint on partitioned table must include
-- all partitioning columns"). No child partitions are created yet — nothing writes to either table until Phase 3.
CREATE TABLE reports (
  id            UUID NOT NULL,
  target_type   TEXT NOT NULL CHECK (target_type IN ('post','comment')),
  target_id     UUID NOT NULL,
  community_id  UUID NOT NULL REFERENCES communities(id),
  reporter_id   UUID NOT NULL REFERENCES users(id),
  reason        TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','resolved','dismissed')),
  resolver_id   UUID REFERENCES users(id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
CREATE INDEX reports_open_idx ON reports (community_id, created_at) WHERE status = 'open';

CREATE TABLE mod_queue (
  community_id       UUID NOT NULL REFERENCES communities(id),
  target_type        TEXT NOT NULL,
  target_id          UUID NOT NULL,
  report_count       INTEGER NOT NULL DEFAULT 0,
  first_reported_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (community_id, target_type, target_id)
);

CREATE TABLE moderation_actions (
  id            UUID NOT NULL,
  community_id  UUID NOT NULL REFERENCES communities(id),
  actor_id      UUID NOT NULL REFERENCES users(id),
  action        TEXT NOT NULL,
  target_type   TEXT NOT NULL,
  target_id     UUID NOT NULL,
  reason        TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
-- app DB role: GRANT INSERT, SELECT ON moderation_actions TO app_role; (no UPDATE/DELETE — append-only). Deferred to
-- Phase 5, once a dedicated non-superuser app role is provisioned for the production deployment.

CREATE TABLE bans (
  community_id  UUID NOT NULL REFERENCES communities(id),
  user_id       UUID NOT NULL REFERENCES users(id),
  issuer_id     UUID NOT NULL REFERENCES users(id),
  reason        TEXT,
  expires_at    TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (community_id, user_id)
);
CREATE INDEX bans_active_idx ON bans (expires_at) WHERE expires_at IS NOT NULL;
