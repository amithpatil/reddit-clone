package com.redditclone.vote;

import java.util.UUID;

// Unlike the plan's own sketch (which only carries the new direction, and nothing at all for a
// "_removed" event), this carries both the direction being replaced and the one taking its place: the
// OutboxWorker needs both to compute a correct net delta when a vote is CHANGED (e.g. up -> down is a
// swing of 2, not 1) or removed (a swing of -oldDirection, not 0 — the plan's sketch's OutboxWorker
// hard-codes delta=0 for every "_removed" event, meaning it never actually reverses a removed vote's
// score contribution). null means "no prior vote" (cast) or "no new vote" (removed).
record VoteEventPayload(UUID targetId, Integer oldDirection, Integer newDirection) {
}
