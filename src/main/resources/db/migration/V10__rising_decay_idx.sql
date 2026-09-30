-- RankDecayJob's periodic sweep (WHERE rising_rank > 0 AND NOT removed AND rising_updated_at > ...) had
-- no index to support it: posts_rising_idx (V9) is keyed on community_id first, so it can't serve a
-- community-agnostic lookup, forcing a full scan of every non-removed post every 5 minutes regardless of
-- how much of the table is actually recently active. This index matches the decay job's WHERE clause
-- directly (rising_rank/rising_updated_at only decay together, never independently, so no query needs
-- this ordered any other way).
CREATE INDEX posts_rising_active_idx ON posts (rising_updated_at) WHERE rising_rank > 0 AND NOT removed;
