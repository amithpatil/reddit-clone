-- posts_new_idx (V3) covered (community_id, created_at DESC) but PostRepository.findNewPage's keyset
-- query seeks and orders on (created_at DESC, id DESC) — the id tiebreaker had no index support, forcing
-- an extra in-memory sort step for any rows sharing an exact created_at at a page boundary.
DROP INDEX posts_new_idx;
CREATE INDEX posts_new_idx ON posts (community_id, created_at DESC, id DESC) WHERE NOT removed;
