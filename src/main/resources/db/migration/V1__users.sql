CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS ltree;

CREATE TABLE users (
  id             UUID PRIMARY KEY,
  username       CITEXT NOT NULL UNIQUE,
  email          CITEXT NOT NULL UNIQUE,
  password_hash  TEXT NOT NULL,
  status         TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','deleted','banned')),
  karma_post     INTEGER NOT NULL DEFAULT 0,
  karma_comment  INTEGER NOT NULL DEFAULT 0,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_settings (
  user_id        UUID PRIMARY KEY REFERENCES users(id),
  nsfw_blur      BOOLEAN NOT NULL DEFAULT true,
  privacy_prefs  JSONB NOT NULL DEFAULT '{}'
);

CREATE TABLE refresh_tokens (
  id           UUID PRIMARY KEY,
  user_id      UUID NOT NULL REFERENCES users(id),
  token_hash   TEXT NOT NULL UNIQUE,
  family_id    UUID NOT NULL,
  expires_at   TIMESTAMPTZ NOT NULL,
  revoked_at   TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
