CREATE TABLE follows (
  follower_id  UUID NOT NULL REFERENCES users(id),
  followee_id  UUID NOT NULL REFERENCES users(id),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (follower_id, followee_id),
  CHECK (follower_id <> followee_id)
);
CREATE INDEX follows_followee_idx ON follows (followee_id, follower_id);

ALTER TABLE users ADD COLUMN follower_count INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN following_count INT NOT NULL DEFAULT 0;
