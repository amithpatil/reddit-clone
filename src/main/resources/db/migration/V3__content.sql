CREATE TABLE posts (
  id             UUID PRIMARY KEY,
  community_id   UUID NOT NULL REFERENCES communities(id),
  author_id      UUID NOT NULL REFERENCES users(id),
  kind           TEXT NOT NULL CHECK (kind IN ('text','link','image','video')),
  title          VARCHAR(300) NOT NULL,
  body           TEXT,
  url            TEXT,
  nsfw           BOOLEAN NOT NULL DEFAULT false,
  spoiler        BOOLEAN NOT NULL DEFAULT false,
  score          INTEGER NOT NULL DEFAULT 0,
  comment_count  INTEGER NOT NULL DEFAULT 0,
  hot_rank       DOUBLE PRECISION NOT NULL DEFAULT 0,
  pinned         BOOLEAN NOT NULL DEFAULT false,
  locked         BOOLEAN NOT NULL DEFAULT false,
  removed        BOOLEAN NOT NULL DEFAULT false,
  search_vector  TSVECTOR,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX posts_hot_idx    ON posts (community_id, hot_rank DESC) WHERE NOT removed;
CREATE INDEX posts_new_idx    ON posts (community_id, created_at DESC) WHERE NOT removed;
CREATE INDEX posts_top_idx    ON posts (community_id, score DESC, created_at DESC) WHERE NOT removed;
CREATE INDEX posts_search_idx ON posts USING gin (search_vector);

CREATE FUNCTION posts_search_vector_update() RETURNS trigger AS $$
BEGIN
  NEW.search_vector := setweight(to_tsvector('english', coalesce(NEW.title,'')), 'A') ||
                        setweight(to_tsvector('english', coalesce(NEW.body,'')), 'B');
  RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER posts_search_vector_trigger
  BEFORE INSERT OR UPDATE ON posts
  FOR EACH ROW EXECUTE FUNCTION posts_search_vector_update();

-- post_id/parent_id intentionally carry no REFERENCES clause (see Data model — Resolved design decisions:
-- post_votes, comment_votes and comments skip DB-level FKs for write throughput; integrity is enforced in
-- the service layer plus a nightly reconciliation job).
CREATE TABLE comments (
  id           UUID PRIMARY KEY,
  post_id      UUID NOT NULL,
  parent_id    UUID,
  path         LTREE NOT NULL,
  depth        SMALLINT NOT NULL,
  author_id    UUID NOT NULL REFERENCES users(id),
  body         TEXT NOT NULL CHECK (char_length(body) <= 10000),
  score        INTEGER NOT NULL DEFAULT 0,
  child_count  INTEGER NOT NULL DEFAULT 0,
  removed      BOOLEAN NOT NULL DEFAULT false,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX comments_post_path_idx ON comments (post_id, path);
CREATE INDEX comments_post_top_idx  ON comments (post_id, score DESC) WHERE parent_id IS NULL AND NOT removed;
