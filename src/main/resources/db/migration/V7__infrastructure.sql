CREATE TABLE media (
  id                 UUID PRIMARY KEY,
  owner_id           UUID NOT NULL REFERENCES users(id),
  r2_key             TEXT NOT NULL UNIQUE,
  content_type       TEXT NOT NULL,
  byte_size          BIGINT NOT NULL,
  processing_status  TEXT NOT NULL DEFAULT 'pending' CHECK (processing_status IN ('pending','ready','failed')),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
  id            UUID PRIMARY KEY,
  event_type    TEXT NOT NULL,
  payload       JSONB NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at  TIMESTAMPTZ
);
CREATE INDEX outbox_unprocessed_idx ON outbox_events (created_at) WHERE processed_at IS NULL;
