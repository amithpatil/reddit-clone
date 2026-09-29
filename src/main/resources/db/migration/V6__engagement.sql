-- notifications is RANGE-partitioned by created_at — same composite-PK fix as reports/moderation_actions (V5).
CREATE TABLE notifications (
  id          UUID NOT NULL,
  user_id     UUID NOT NULL REFERENCES users(id),
  type        TEXT NOT NULL,
  source      JSONB NOT NULL,
  read_at     TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
CREATE INDEX notifications_unread_idx ON notifications (user_id, created_at DESC) WHERE read_at IS NULL;

CREATE TABLE messages (
  id            UUID PRIMARY KEY,
  sender_id     UUID NOT NULL REFERENCES users(id),
  recipient_id  UUID NOT NULL REFERENCES users(id),
  subject       VARCHAR(200),
  body          TEXT NOT NULL,
  read_at       TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX messages_recipient_idx ON messages (recipient_id, created_at DESC);

CREATE TABLE saved_items (
  user_id      UUID NOT NULL REFERENCES users(id),
  target_type  TEXT NOT NULL CHECK (target_type IN ('post','comment')),
  target_id    UUID NOT NULL,
  saved_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, target_type, target_id)
);

CREATE TABLE hidden_items (
  user_id      UUID NOT NULL REFERENCES users(id),
  target_type  TEXT NOT NULL,
  target_id    UUID NOT NULL,
  hidden_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, target_type, target_id)
);
