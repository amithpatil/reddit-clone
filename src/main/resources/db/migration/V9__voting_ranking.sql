-- Phase 2: columns and indexes needed to precompute the hot/top/rising/controversial post-feed sorts
-- and the best (Wilson score) comment sort, so a listing read is always a plain indexed ORDER BY per
-- the source plan (Detailed class reference — Search & feed caching), never a runtime calculation.
-- ups/downs are tracked separately from the net `score` because the Wilson-score and controversial
-- formulas need the raw vote split, not just the net total.

ALTER TABLE posts
  ADD COLUMN ups                INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN downs               INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN controversial_rank  DOUBLE PRECISION NOT NULL DEFAULT 0,
  ADD COLUMN rising_rank         DOUBLE PRECISION NOT NULL DEFAULT 0,
  ADD COLUMN rising_updated_at   TIMESTAMPTZ NOT NULL DEFAULT now();

-- posts_hot_idx (V3) and posts_top_idx (V3) never had an id tiebreaker, same gap V8 already fixed for
-- posts_new_idx: keyset pagination on hot_rank/score alone can't totally order rows that tie exactly,
-- forcing an in-memory sort step at that page boundary.
DROP INDEX posts_hot_idx;
CREATE INDEX posts_hot_idx ON posts (community_id, hot_rank DESC, id DESC) WHERE NOT removed;

DROP INDEX posts_top_idx;
CREATE INDEX posts_top_idx ON posts (community_id, score DESC, id DESC) WHERE NOT removed;

CREATE INDEX posts_controversial_idx ON posts (community_id, controversial_rank DESC, id DESC) WHERE NOT removed;
CREATE INDEX posts_rising_idx ON posts (community_id, rising_rank DESC, id DESC) WHERE NOT removed;

-- Real Reddit's "Best" is a comment-thread sort (Wilson score lower bound), not a subreddit feed sort —
-- confirmed against this plan's own API design table, which lists exactly five `/r/{name}/{sort}`
-- values (hot, new, top, rising, controversial) and no "best". So best_rank lives on comments, not posts.
ALTER TABLE comments
  ADD COLUMN ups        INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN downs      INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN best_rank  DOUBLE PRECISION NOT NULL DEFAULT 0;

CREATE INDEX comments_post_best_idx ON comments (post_id, best_rank DESC, id DESC) WHERE parent_id IS NULL AND NOT removed;

-- ShedLock's required schema (net.javacrumbs.shedlock:shedlock-provider-jdbc-template) — coordinates the
-- OutboxWorker/ReconciliationJob/RankDecayJob across instances. Column list confirmed against the actual
-- generated SQL (a live run threw "column locked_by does not exist" against an earlier, docs-remembered
-- version of this table that used the older shedlock 4.x/5.x name `locking_process`): 7.10.1 names it
-- `locked_by`.
CREATE TABLE shedlock (
  name        VARCHAR(64) NOT NULL PRIMARY KEY,
  lock_until  TIMESTAMP(3) NOT NULL,
  locked_at   TIMESTAMP(3) NOT NULL,
  locked_by   VARCHAR(255) NOT NULL
);
