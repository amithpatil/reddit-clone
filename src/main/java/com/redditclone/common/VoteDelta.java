package com.redditclone.common;

/**
 * One target's (post or comment) net change from a batch of outbox vote events, grouped by the
 * OutboxWorker before it's applied — see Voting, karma & outbox in the source plan. eventCount is the
 * number of individual cast/removed events folded into this delta (not the net score change), used by
 * post ranking to measure vote velocity for "rising".
 */
public record VoteDelta(int scoreDelta, int upsDelta, int downsDelta, int eventCount) {
}
