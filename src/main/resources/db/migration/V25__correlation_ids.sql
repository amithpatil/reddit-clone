ALTER TABLE outbox_events ADD COLUMN correlation_id TEXT;
ALTER TABLE media ADD COLUMN correlation_id TEXT;
