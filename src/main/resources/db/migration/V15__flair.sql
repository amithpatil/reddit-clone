ALTER TABLE flairs ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX flairs_community_idx ON flairs (community_id);

-- ON DELETE SET NULL: a mod pruning their flair list is a normal, expected action — it must not be
-- blocked by an FK violation just because some post or member still references the deleted flair, and it
-- must not cascade-delete the post/membership either. Same non-destructive philosophy as the rest of this
-- schema (e.g. account deletion anonymizes rather than cascades).
ALTER TABLE posts ADD COLUMN flair_id UUID REFERENCES flairs(id) ON DELETE SET NULL;
ALTER TABLE memberships ADD COLUMN flair_id UUID REFERENCES flairs(id) ON DELETE SET NULL;
