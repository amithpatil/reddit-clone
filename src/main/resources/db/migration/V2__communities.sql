CREATE TABLE communities (
  id                UUID PRIMARY KEY,
  name              CITEXT NOT NULL UNIQUE,
  type              TEXT NOT NULL CHECK (type IN ('public','restricted','private')),
  description       TEXT,
  rules             JSONB NOT NULL DEFAULT '[]',
  creator_id        UUID NOT NULL REFERENCES users(id),
  subscriber_count  INTEGER NOT NULL DEFAULT 0,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX communities_name_trgm_idx ON communities USING gin (name gin_trgm_ops);

CREATE TABLE memberships (
  user_id       UUID NOT NULL REFERENCES users(id),
  community_id  UUID NOT NULL REFERENCES communities(id),
  joined_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, community_id)
);
CREATE INDEX memberships_community_idx ON memberships (community_id, user_id);

CREATE TABLE community_moderators (
  community_id  UUID NOT NULL REFERENCES communities(id),
  user_id       UUID NOT NULL REFERENCES users(id),
  permissions   INTEGER NOT NULL DEFAULT 0,
  added_by      UUID REFERENCES users(id),
  added_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (community_id, user_id)
);

CREATE TABLE flairs (
  id            UUID PRIMARY KEY,
  community_id  UUID NOT NULL REFERENCES communities(id),
  text          VARCHAR(64) NOT NULL,
  color         VARCHAR(7) NOT NULL,
  type          TEXT NOT NULL CHECK (type IN ('user','post'))
);
