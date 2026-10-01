CREATE TABLE community_join_requests (
  community_id  UUID NOT NULL REFERENCES communities(id),
  user_id       UUID NOT NULL REFERENCES users(id),
  status        TEXT NOT NULL CHECK (status IN ('pending','approved','denied')),
  requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_by    UUID REFERENCES users(id),
  decided_at    TIMESTAMPTZ,
  PRIMARY KEY (community_id, user_id)
);
CREATE INDEX community_join_requests_pending_idx ON community_join_requests (community_id) WHERE status = 'pending';

-- Deliberately separate from community_join_requests, not a unified "approved users" table: restricted
-- has no pending state at all (a mod grants posting rights directly), so forcing it through a status
-- column designed for private's request workflow would be the wrong shape for a simpler concept.
CREATE TABLE community_approved_submitters (
  community_id  UUID NOT NULL REFERENCES communities(id),
  user_id       UUID NOT NULL REFERENCES users(id),
  approved_by   UUID REFERENCES users(id),
  approved_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (community_id, user_id)
);
