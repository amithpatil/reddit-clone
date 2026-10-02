ALTER TABLE posts DROP CONSTRAINT posts_kind_check;
ALTER TABLE posts ADD CONSTRAINT posts_kind_check CHECK (kind IN ('text','link','image','video','gallery'));

CREATE TABLE post_media (
  post_id   UUID NOT NULL REFERENCES posts(id),
  media_id  UUID NOT NULL REFERENCES media(id),
  position  SMALLINT NOT NULL,
  PRIMARY KEY (post_id, media_id),
  UNIQUE (post_id, position)
);
