-- user_id/post_id/comment_id intentionally carry no REFERENCES clause, same reasoning as comments (V3).
CREATE TABLE post_votes (
  user_id    UUID NOT NULL,
  post_id    UUID NOT NULL,
  direction  SMALLINT NOT NULL CHECK (direction IN (-1, 1)),
  voted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, post_id)
);

CREATE TABLE comment_votes (
  user_id     UUID NOT NULL,
  comment_id  UUID NOT NULL,
  direction   SMALLINT NOT NULL CHECK (direction IN (-1, 1)),
  voted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, comment_id)
);

CREATE TABLE karma_log (
  id          UUID PRIMARY KEY,
  user_id     UUID NOT NULL REFERENCES users(id),
  delta       INTEGER NOT NULL,
  reason      TEXT NOT NULL,
  source_id   UUID,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX karma_log_user_idx ON karma_log (user_id, created_at DESC);

CREATE TABLE awards (
  id    SMALLINT PRIMARY KEY,
  name  TEXT NOT NULL UNIQUE,
  icon  TEXT NOT NULL
);

CREATE TABLE post_awards (
  post_id    UUID NOT NULL REFERENCES posts(id),
  award_id   SMALLINT NOT NULL REFERENCES awards(id),
  giver_id   UUID NOT NULL REFERENCES users(id),
  given_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (post_id, award_id, giver_id)
);
