CREATE INDEX users_username_trgm_idx ON users USING gin (username gin_trgm_ops);
