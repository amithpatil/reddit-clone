package com.redditclone.common;

/**
 * One target's (post or comment) net change from a batch of outbox vote events, grouped by the
 * OutboxWorker before it's applied — see Voting, karma & outbox in the source plan.
 */
public record VoteDelta(int scoreDelta, int upsDelta, int downsDelta) {
}
