-- Neither V22's PK (follower_id, followee_id) nor its follows_followee_idx (followee_id, follower_id)
-- include created_at, but both FollowRepository keyset queries filter on one id column and
-- ORDER BY created_at DESC — forcing a filesort once a page doesn't fit in a single index scan. Same
-- covering-index shape as V8__posts_new_idx_covering.sql (filter column(s), then the ORDER BY columns).
DROP INDEX follows_followee_idx;
CREATE INDEX follows_followee_idx ON follows (followee_id, created_at DESC, follower_id DESC);
CREATE INDEX follows_follower_idx ON follows (follower_id, created_at DESC, followee_id DESC);
