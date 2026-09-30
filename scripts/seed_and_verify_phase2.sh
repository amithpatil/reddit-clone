#!/usr/bin/env bash
# Seeds a small dataset through the live Phase 2 REST API (never direct SQL for content creation —
# the two exceptions are explicitly called out below, where the whole point is simulating pre-existing
# bad data for the reconciliation job) and re-verifies voting, karma, and feed-ranking end to end:
# outbox processing lag, vote switch/idempotent-revote/removal, the concurrent-duplicate-vote race fix,
# the vote-on-nonexistent-target 404 fix, hot_rank initialization at post creation, the four post-feed
# sorts (hot/top/rising/controversial), the Wilson-score "best" comment sort, the rank-cursor
# discriminator fix, and the ReconciliationJob/RankDecayJob SQL logic (checked directly via psql against
# their exact queries, not by waiting for the real 5-minute/nightly schedule). Prints PASS/FAIL with the
# real observed value for every check. Data is left in the dev database afterward.
#
# Prereqs: docker compose stack up, app running on $BASE (see application.yml for the port).

set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5434}"
PGUSER="${PGUSER:-app}"
PGDATABASE="${PGDATABASE:-redditclone}"
export PGPASSWORD="${PGPASSWORD:-devpassword}"

RUN=$(date +%s | tail -c 6)   # short run-scoped suffix so re-runs never collide with prior seed data
PASSWORD="Sup3rSecret!1"
TICK_WAIT=3   # OutboxWorker ticks every 2s (fixedDelay=2000); 3s gives it room to land

PASS_COUNT=0
FAIL_COUNT=0
INFO_COUNT=0
declare -a RESULTS=()

psql_c() { psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -t -A -c "$1"; }

record() {
  local status="$1" desc="$2" detail="$3"
  RESULTS+=("${status}|${desc}|${detail}")
  case "$status" in
    PASS) PASS_COUNT=$((PASS_COUNT+1)); printf 'PASS  %-70s %s\n' "$desc" "$detail" ;;
    FAIL) FAIL_COUNT=$((FAIL_COUNT+1)); printf 'FAIL  %-70s %s\n' "$desc" "$detail" ;;
    INFO) INFO_COUNT=$((INFO_COUNT+1)); printf 'INFO  %-70s %s\n' "$desc" "$detail" ;;
  esac
}

expect_status() {
  local desc="$1" expected="$2" actual="$3" detail="$4"
  if [ "$actual" = "$expected" ]; then
    record PASS "$desc" "expected $expected, got $actual — $detail"
  else
    record FAIL "$desc" "expected $expected, got $actual — $detail"
  fi
}

# request helper: sets HTTP_STATUS and HTTP_BODY globals
req() {
  local method="$1" path="$2" data="${3:-}" ; shift 3 || true
  local resp
  resp=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" -H 'Content-Type: application/json' \
    ${data:+-d "$data"} "$@")
  HTTP_STATUS=$(echo "$resp" | tail -n1)
  HTTP_BODY=$(echo "$resp" | sed '$d')
}

vote() { # userToken targetType targetId dir
  curl -s -o /dev/null -X POST "$BASE/api/vote" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $1" -d "{\"targetType\":\"$2\",\"targetId\":\"$3\",\"dir\":$4}"
}

echo "=== Baseline table counts (before seeding) ==="
BASELINE=$(psql_c "SELECT 'post_votes',count(*) FROM post_votes UNION ALL SELECT 'comment_votes',count(*) FROM comment_votes UNION ALL SELECT 'outbox_events',count(*) FROM outbox_events UNION ALL SELECT 'karma_log',count(*) FROM karma_log;")
echo "$BASELINE"
echo ""

################################################################################
echo "=== Phase A: seeding via the live API (run suffix: $RUN) ==="
################################################################################

declare -a TOKENS=()
N_USERS=16   # TOKENS[1] = author; TOKENS[2..16] = 15 voters (enough for the 8up/7down Wilson-score demo)
for i in $(seq 1 $N_USERS); do
  uname="p2seed${RUN}u${i}"
  email="p2seed${RUN}u${i}@example.com"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$email\",\"password\":\"$PASSWORD\"}"
  if [ "$HTTP_STATUS" = "200" ]; then
    TOKENS[$i]=$(echo "$HTTP_BODY" | jq -r .accessToken)
  else
    echo "FATAL: failed to register $uname -> $HTTP_STATUS $HTTP_BODY" >&2
    exit 1
  fi
done
req GET /api/v1/me "" -H "Authorization: Bearer ${TOKENS[1]}"
AUTHOR_ID=$(echo "$HTTP_BODY" | jq -r .id)
record PASS "seed: register $N_USERS users" "all returned 200 with accessToken; author id=$AUTHOR_ID"

RANK_COMM="p2rank${RUN}"
req POST /r "{\"name\":\"$RANK_COMM\",\"description\":\"phase 2 ranking test\"}" -H "Authorization: Bearer ${TOKENS[1]}"
if [ "$HTTP_STATUS" != "200" ]; then
  echo "FATAL: failed to create community $RANK_COMM -> $HTTP_STATUS $HTTP_BODY" >&2
  exit 1
fi
record PASS "seed: create dedicated community $RANK_COMM" "isolates ranking-order assertions from any other seeded content"

submit_post() { # title -> sets POST_ID
  req POST "/r/$RANK_COMM/submit" "{\"kind\":\"text\",\"title\":\"$1\",\"body\":\"phase 2 seed\"}" \
    -H "Authorization: Bearer ${TOKENS[1]}" -H "Idempotency-Key: p2-${RUN}-$1"
  POST_ID=$(echo "$HTTP_BODY" | jq -r .id)
}

################################################################################
echo ""
echo "=== Checkpoint: hot_rank is initialized at creation, not left at 0 ==="
################################################################################
submit_post "fresh"
FRESH_ID="$POST_ID"
FRESH_HOT_RANK=$(echo "$HTTP_BODY" | jq -r .hotRank)
if awk "BEGIN{exit !($FRESH_HOT_RANK > 0)}"; then
  record PASS "hot_rank set at insert time (fix for the never-voted-post bug)" "a brand-new, never-voted post has hotRank=$FRESH_HOT_RANK (nonzero, time-driven), not the schema default of 0"
else
  record FAIL "hot_rank set at insert time (fix for the never-voted-post bug)" "hotRank=$FRESH_HOT_RANK — expected > 0 immediately at creation"
fi

################################################################################
echo ""
echo "=== Checkpoint: feed-ranking posts (hot/top/rising/controversial ordering) ==="
################################################################################
submit_post "low-score"
LOW_ID="$POST_ID"
submit_post "controversial"
CONTROVERSIAL_ID="$POST_ID"
submit_post "hot"
HOT_ID="$POST_ID"

vote "${TOKENS[2]}" post "$LOW_ID" 1
vote "${TOKENS[3]}" post "$CONTROVERSIAL_ID" 1
vote "${TOKENS[4]}" post "$CONTROVERSIAL_ID" 1
vote "${TOKENS[5]}" post "$CONTROVERSIAL_ID" 1
vote "${TOKENS[6]}" post "$CONTROVERSIAL_ID" -1
vote "${TOKENS[7]}" post "$CONTROVERSIAL_ID" -1
vote "${TOKENS[8]}" post "$CONTROVERSIAL_ID" -1
for i in $(seq 2 11); do vote "${TOKENS[$i]}" post "$HOT_ID" 1; done
sleep $TICK_WAIT
sleep $TICK_WAIT

req GET "/r/$RANK_COMM/hot" ""
HOT_FIRST=$(echo "$HTTP_BODY" | jq -r '.data.children[0].data.id')
if [ "$HOT_FIRST" = "$HOT_ID" ]; then
  record PASS "GET /hot — heaviest-upvoted post ranks first" "first=$HOT_FIRST (10 upvotes) matches expected"
else
  record FAIL "GET /hot — heaviest-upvoted post ranks first" "first=$HOT_FIRST expected=$HOT_ID"
fi

req GET "/r/$RANK_COMM/top" ""
TOP_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id')
TOP_AFTER=$(echo "$HTTP_BODY" | jq -r '.data.after')
TOP_ORDER_OK="false"
[ "$(echo "$TOP_IDS" | sed -n '1p')" = "$HOT_ID" ] && [ "$(echo "$TOP_IDS" | sed -n '2p')" = "$LOW_ID" ] && TOP_ORDER_OK="true"
if [ "$TOP_ORDER_OK" = "true" ]; then
  record PASS "GET /top — orders by raw score (hot=10, low=1, ...)" "order matches: hot then low"
else
  record FAIL "GET /top — orders by raw score (hot=10, low=1, ...)" "got order: $(echo "$TOP_IDS" | tr '\n' ' ')"
fi

req GET "/r/$RANK_COMM/top?t=hour" ""
TOP_HOUR_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id' | sort)
TOP_ALL_IDS_SORTED=$(echo "$TOP_IDS" | sort)
if [ "$TOP_HOUR_IDS" = "$TOP_ALL_IDS_SORTED" ]; then
  record PASS "GET /top?t=hour — same result set as t=all for posts created seconds ago" "identical id sets"
else
  record FAIL "GET /top?t=hour — same result set as t=all for posts created seconds ago" "hour set differs from all set"
fi

req GET "/r/$RANK_COMM/controversial" ""
CONTROVERSIAL_FIRST=$(echo "$HTTP_BODY" | jq -r '.data.children[0].data.id')
CONTROVERSIAL_RANK_VAL=$(echo "$HTTP_BODY" | jq -r '.data.children[0].data.controversialRank')
if [ "$CONTROVERSIAL_FIRST" = "$CONTROVERSIAL_ID" ] && awk "BEGIN{exit !($CONTROVERSIAL_RANK_VAL > 0)}"; then
  record PASS "GET /controversial — the only split-vote (3up/3down) post ranks first" "first=$CONTROVERSIAL_FIRST controversialRank=$CONTROVERSIAL_RANK_VAL"
else
  record FAIL "GET /controversial — the only split-vote (3up/3down) post ranks first" "first=$CONTROVERSIAL_FIRST rank=$CONTROVERSIAL_RANK_VAL expected=$CONTROVERSIAL_ID with rank>0"
fi

req GET "/r/$RANK_COMM/rising" ""
RISING_FIRST=$(echo "$HTTP_BODY" | jq -r '.data.children[0].data.id')
RISING_RANK_VAL=$(echo "$HTTP_BODY" | jq -r '.data.children[0].data.risingRank')
if [ "$RISING_FIRST" = "$HOT_ID" ]; then
  record PASS "GET /rising — the post with the freshest vote burst (10 votes just now) ranks first" "first=$RISING_FIRST risingRank=$RISING_RANK_VAL"
else
  record FAIL "GET /rising — the post with the freshest vote burst (10 votes just now) ranks first" "first=$RISING_FIRST expected=$HOT_ID"
fi

################################################################################
echo ""
echo "=== Checkpoint: rank-cursor discriminator (fix for cross-endpoint cursor reuse) ==="
################################################################################
if [ -n "$TOP_AFTER" ] && [ "$TOP_AFTER" != "null" ]; then
  req GET "/r/$RANK_COMM/hot?after=$TOP_AFTER" ""
  expect_status "a /top cursor fed into /hot is rejected, not silently misread" "400" "$HTTP_STATUS" "body=$HTTP_BODY"
else
  # fewer than PAGE_SIZE posts, so /top never emits a real after token — craft one by hand instead
  CRAFTED=$(printf 'top:0.0:%s' "$LOW_ID" | base64 | tr '+/' '-_' | tr -d '=\n')
  req GET "/r/$RANK_COMM/hot?after=$CRAFTED" ""
  expect_status "a /top-tagged cursor fed into /hot is rejected, not silently misread" "400" "$HTTP_STATUS" "body=$HTTP_BODY"
fi

################################################################################
echo ""
echo "=== Checkpoint: best-rank (Wilson score) comment sort ==="
################################################################################
submit_post "comments"
COMMENTS_POST_ID="$POST_ID"
req POST /api/comment "{\"postId\":\"$COMMENTS_POST_ID\",\"body\":\"small sample, all up\"}" -H "Authorization: Bearer ${TOKENS[1]}"
COMMENT_A=$(echo "$HTTP_BODY" | jq -r .id)
req POST /api/comment "{\"postId\":\"$COMMENTS_POST_ID\",\"body\":\"big sample, same net score\"}" -H "Authorization: Bearer ${TOKENS[1]}"
COMMENT_B=$(echo "$HTTP_BODY" | jq -r .id)

vote "${TOKENS[2]}" comment "$COMMENT_A" 1   # A: 1 up, 0 down -> score 1, n=1
for i in $(seq 2 9);  do vote "${TOKENS[$i]}" comment "$COMMENT_B" 1;  done   # B: 8 up
for i in $(seq 10 16); do vote "${TOKENS[$i]}" comment "$COMMENT_B" -1; done  # B: 7 down -> score 1, n=15
sleep $TICK_WAIT
sleep $TICK_WAIT

req GET "/r/$RANK_COMM/comments/$COMMENTS_POST_ID" ""
FIRST_COMMENT_ID=$(echo "$HTTP_BODY" | jq -r '.comments[0].id')
A_SCORE=$(echo "$HTTP_BODY" | jq -r ".comments[] | select(.id==\"$COMMENT_A\") | .score")
B_SCORE=$(echo "$HTTP_BODY" | jq -r ".comments[] | select(.id==\"$COMMENT_B\") | .score")
A_BEST=$(echo "$HTTP_BODY" | jq -r ".comments[] | select(.id==\"$COMMENT_A\") | .bestRank")
B_BEST=$(echo "$HTTP_BODY" | jq -r ".comments[] | select(.id==\"$COMMENT_B\") | .bestRank")
if [ "$A_SCORE" = "$B_SCORE" ] && [ "$FIRST_COMMENT_ID" = "$COMMENT_B" ] && awk "BEGIN{exit !($B_BEST > $A_BEST)}"; then
  record PASS "equal-score comments ranked by Wilson confidence, not raw score" "A(1 up/0 down): score=$A_SCORE bestRank=$A_BEST — B(8 up/7 down): score=$B_SCORE bestRank=$B_BEST — B ranks first"
else
  record FAIL "equal-score comments ranked by Wilson confidence, not raw score" "A score=$A_SCORE bestRank=$A_BEST — B score=$B_SCORE bestRank=$B_BEST — first=$FIRST_COMMENT_ID expected B first with equal scores"
fi

################################################################################
echo ""
echo "=== Checkpoint: vote switch is a swing of 2, not 1 ==="
################################################################################
submit_post "switch"
SWITCH_ID="$POST_ID"
vote "${TOKENS[2]}" post "$SWITCH_ID" 1
sleep $TICK_WAIT
SCORE_AFTER_UP=$(psql_c "SELECT score FROM posts WHERE id='$SWITCH_ID';")
vote "${TOKENS[2]}" post "$SWITCH_ID" -1
sleep $TICK_WAIT
SCORE_AFTER_SWITCH=$(psql_c "SELECT score FROM posts WHERE id='$SWITCH_ID';")
if [ "$SCORE_AFTER_UP" = "1" ] && [ "$SCORE_AFTER_SWITCH" = "-1" ]; then
  record PASS "up -> down vote switch swings score by 2" "after upvote: $SCORE_AFTER_UP, after switch to downvote: $SCORE_AFTER_SWITCH"
else
  record FAIL "up -> down vote switch swings score by 2" "after upvote: $SCORE_AFTER_UP (expected 1), after switch: $SCORE_AFTER_SWITCH (expected -1)"
fi

################################################################################
echo ""
echo "=== Checkpoint: re-casting the same direction is a no-op (no duplicate outbox event) ==="
################################################################################
submit_post "idempotent"
IDEMPOTENT_ID="$POST_ID"
vote "${TOKENS[2]}" post "$IDEMPOTENT_ID" 1
sleep $TICK_WAIT
EVENTS_BEFORE=$(psql_c "SELECT count(*) FROM outbox_events;")
vote "${TOKENS[2]}" post "$IDEMPOTENT_ID" 1
sleep 1
EVENTS_AFTER=$(psql_c "SELECT count(*) FROM outbox_events;")
SCORE_IDEMPOTENT=$(psql_c "SELECT score FROM posts WHERE id='$IDEMPOTENT_ID';")
if [ "$EVENTS_BEFORE" = "$EVENTS_AFTER" ] && [ "$SCORE_IDEMPOTENT" = "1" ]; then
  record PASS "re-voting the same direction writes no new outbox event" "outbox_events count unchanged ($EVENTS_BEFORE), score stays 1"
else
  record FAIL "re-voting the same direction writes no new outbox event" "events before=$EVENTS_BEFORE after=$EVENTS_AFTER score=$SCORE_IDEMPOTENT"
fi

################################################################################
echo ""
echo "=== Checkpoint: vote removal reverses the delta; removing twice 404s ==="
################################################################################
submit_post "remove"
REMOVE_ID="$POST_ID"
vote "${TOKENS[2]}" post "$REMOVE_ID" 1
sleep $TICK_WAIT
req DELETE "/api/vote?targetType=post&targetId=$REMOVE_ID" "" -H "Authorization: Bearer ${TOKENS[2]}"
REMOVE_STATUS_1="$HTTP_STATUS"
sleep $TICK_WAIT
SCORE_AFTER_REMOVE=$(psql_c "SELECT score FROM posts WHERE id='$REMOVE_ID';")
req DELETE "/api/vote?targetType=post&targetId=$REMOVE_ID" "" -H "Authorization: Bearer ${TOKENS[2]}"
if [ "$REMOVE_STATUS_1" = "200" ] && [ "$SCORE_AFTER_REMOVE" = "0" ] && [ "$HTTP_STATUS" = "404" ]; then
  record PASS "unvote reverses the score; a second unvote 404s" "first remove=200 score-after=$SCORE_AFTER_REMOVE second remove=404"
else
  record FAIL "unvote reverses the score; a second unvote 404s" "first remove=$REMOVE_STATUS_1 score-after=$SCORE_AFTER_REMOVE second remove=$HTTP_STATUS"
fi

################################################################################
echo ""
echo "=== Checkpoint: concurrent duplicate votes don't double-count (TOCTOU race fix) ==="
################################################################################
submit_post "race"
RACE_ID="$POST_ID"
req POST /api/v1/register "{\"username\":\"p2seed${RUN}racer\",\"email\":\"p2seed${RUN}racer@example.com\",\"password\":\"$PASSWORD\"}"
RACER_TOKEN=$(echo "$HTTP_BODY" | jq -r .accessToken)
for i in $(seq 1 8); do
  vote "$RACER_TOKEN" post "$RACE_ID" 1 &
done
wait
sleep $TICK_WAIT
sleep $TICK_WAIT
RACE_SCORE=$(psql_c "SELECT score FROM posts WHERE id='$RACE_ID';")
RACE_VOTE_ROWS=$(psql_c "SELECT count(*) FROM post_votes WHERE post_id='$RACE_ID';")
if [ "$RACE_SCORE" = "1" ] && [ "$RACE_VOTE_ROWS" = "1" ]; then
  record PASS "8 concurrent identical upvotes from one user settle to score=1, not 8" "score=$RACE_SCORE post_votes rows=$RACE_VOTE_ROWS"
else
  record FAIL "8 concurrent identical upvotes from one user settle to score=1, not 8" "score=$RACE_SCORE (expected 1) post_votes rows=$RACE_VOTE_ROWS (expected 1)"
fi

################################################################################
echo ""
echo "=== Checkpoint: karma is internally consistent (users.karma_post == sum(posts.score) == sum(karma_log)) ==="
################################################################################
KARMA_FROM_API=$(curl -s "$BASE/api/v1/me" -H "Authorization: Bearer ${TOKENS[1]}" | jq -r .karmaPost)
KARMA_FROM_COLUMN=$(psql_c "SELECT karma_post FROM users WHERE id='$AUTHOR_ID';")
KARMA_FROM_POSTS=$(psql_c "SELECT COALESCE(SUM(score),0) FROM posts WHERE author_id='$AUTHOR_ID' AND NOT removed;")
KARMA_FROM_LOG=$(psql_c "SELECT COALESCE(SUM(delta),0) FROM karma_log WHERE user_id='$AUTHOR_ID' AND reason='post_vote';")
if [ "$KARMA_FROM_API" = "$KARMA_FROM_COLUMN" ] && [ "$KARMA_FROM_COLUMN" = "$KARMA_FROM_POSTS" ] && [ "$KARMA_FROM_POSTS" = "$KARMA_FROM_LOG" ]; then
  record PASS "karma_post, sum(posts.score), and sum(karma_log.delta) all agree" "all four sources report $KARMA_FROM_API"
else
  record FAIL "karma_post, sum(posts.score), and sum(karma_log.delta) all agree" "api=$KARMA_FROM_API column=$KARMA_FROM_COLUMN sum(posts)=$KARMA_FROM_POSTS sum(log)=$KARMA_FROM_LOG"
fi

KARMA_COMMENT_FROM_API=$(curl -s "$BASE/api/v1/me" -H "Authorization: Bearer ${TOKENS[1]}" | jq -r .karmaComment)
KARMA_COMMENT_FROM_COMMENTS=$(psql_c "SELECT COALESCE(SUM(score),0) FROM comments WHERE author_id='$AUTHOR_ID' AND NOT removed;")
if [ "$KARMA_COMMENT_FROM_API" = "$KARMA_COMMENT_FROM_COMMENTS" ]; then
  record PASS "karma_comment agrees with sum(comments.score)" "both report $KARMA_COMMENT_FROM_API"
else
  record FAIL "karma_comment agrees with sum(comments.score)" "api=$KARMA_COMMENT_FROM_API sum(comments)=$KARMA_COMMENT_FROM_COMMENTS"
fi

################################################################################
echo ""
echo "=== Happy-path / failure-path matrix for the voting endpoints ==="
################################################################################
req POST /api/vote "{\"targetType\":\"post\",\"targetId\":\"$LOW_ID\",\"dir\":0}" -H "Authorization: Bearer ${TOKENS[2]}"
expect_status "POST /api/vote — dir=0 rejected" "400" "$HTTP_STATUS" "body=$HTTP_BODY"

req POST /api/vote "{\"targetType\":\"bogus\",\"targetId\":\"$LOW_ID\",\"dir\":1}" -H "Authorization: Bearer ${TOKENS[2]}"
expect_status "POST /api/vote — invalid targetType rejected" "400" "$HTTP_STATUS" "body=$HTTP_BODY"

req POST /api/vote "{\"targetType\":\"post\",\"targetId\":\"$LOW_ID\",\"dir\":1}"
expect_status "POST /api/vote — no auth token" "401" "$HTTP_STATUS" "body=$HTTP_BODY"

req POST /api/vote "{\"targetType\":\"post\",\"targetId\":\"00000000-0000-0000-0000-000000000000\",\"dir\":1}" -H "Authorization: Bearer ${TOKENS[2]}"
expect_status "POST /api/vote — nonexistent post (existence-check fix)" "404" "$HTTP_STATUS" "body=$HTTP_BODY"

req POST /api/vote "{\"targetType\":\"comment\",\"targetId\":\"00000000-0000-0000-0000-000000000000\",\"dir\":1}" -H "Authorization: Bearer ${TOKENS[2]}"
expect_status "POST /api/vote — nonexistent comment (existence-check fix)" "404" "$HTTP_STATUS" "body=$HTTP_BODY"

################################################################################
echo ""
echo "=== ReconciliationJob / RankDecayJob: verifying the SQL logic directly (no waiting) ==="
################################################################################
# Simulating pre-existing bad data, not normal content creation: the API itself now rejects voting on a
# nonexistent target (the existence-check fix above), so the only way to reproduce the orphan-row state
# ReconciliationJob exists to clean up is to insert one directly, the way a pre-fix vote or a hard-deleted
# post could have left behind.
FAKE_POST_ID="00000000-0000-0000-0000-ffffffffffff"
psql_c "INSERT INTO post_votes (user_id, post_id, direction) VALUES ('$AUTHOR_ID', '$FAKE_POST_ID', 1) ON CONFLICT DO NOTHING;" > /dev/null
ORPHAN_BEFORE=$(psql_c "SELECT count(*) FROM post_votes WHERE post_id='$FAKE_POST_ID';")
# ReconciliationJob.run()'s exact statement:
psql_c "DELETE FROM post_votes pv WHERE NOT EXISTS (SELECT 1 FROM posts p WHERE p.id = pv.post_id);" > /dev/null
ORPHAN_AFTER=$(psql_c "SELECT count(*) FROM post_votes WHERE post_id='$FAKE_POST_ID';")
if [ "$ORPHAN_BEFORE" = "1" ] && [ "$ORPHAN_AFTER" = "0" ]; then
  record PASS "ReconciliationJob's orphan post_votes DELETE removes exactly the fabricated orphan row" "before=$ORPHAN_BEFORE after=$ORPHAN_AFTER"
else
  record FAIL "ReconciliationJob's orphan post_votes DELETE removes exactly the fabricated orphan row" "before=$ORPHAN_BEFORE after=$ORPHAN_AFTER"
fi

DECAY_RISING_BEFORE=$(psql_c "SELECT rising_rank FROM posts WHERE id='$HOT_ID';")
# RankDecayJob.decayRising()'s exact statement:
psql_c "UPDATE posts SET rising_rank = CASE WHEN rising_rank * 0.5 < 0.01 THEN 0 ELSE rising_rank * 0.5 END WHERE rising_rank > 0 AND NOT removed AND rising_updated_at > now() - interval '24 hours';" > /dev/null
DECAY_RISING_AFTER=$(psql_c "SELECT rising_rank FROM posts WHERE id='$HOT_ID';")
EXPECTED_DECAY=$(awk "BEGIN{v=$DECAY_RISING_BEFORE*0.5; print (v<0.01)?0:v}")
DECAY_MATCHES=$(awk "BEGIN{print (($DECAY_RISING_AFTER - $EXPECTED_DECAY)^2 < 0.0000001) ? \"yes\" : \"no\"}")
if [ "$DECAY_MATCHES" = "yes" ]; then
  record PASS "RankDecayJob's UPDATE halves a fresh post's rising_rank as expected" "before=$DECAY_RISING_BEFORE after=$DECAY_RISING_AFTER expected=$EXPECTED_DECAY"
else
  record FAIL "RankDecayJob's UPDATE halves a fresh post's rising_rank as expected" "before=$DECAY_RISING_BEFORE after=$DECAY_RISING_AFTER expected=$EXPECTED_DECAY"
fi

IDX_EXISTS=$(psql_c "SELECT count(*) FROM pg_indexes WHERE indexname='posts_rising_active_idx';")
if [ "$IDX_EXISTS" = "1" ]; then
  record PASS "posts_rising_active_idx (V10) exists to bound the decay job's scan" "found in pg_indexes"
else
  record FAIL "posts_rising_active_idx (V10) exists to bound the decay job's scan" "not found in pg_indexes"
fi

################################################################################
echo ""
echo "=== Final table counts (after seeding) ==="
################################################################################
AFTER=$(psql_c "SELECT 'post_votes',count(*) FROM post_votes UNION ALL SELECT 'comment_votes',count(*) FROM comment_votes UNION ALL SELECT 'outbox_events',count(*) FROM outbox_events UNION ALL SELECT 'karma_log',count(*) FROM karma_log;")
echo "$AFTER"

echo ""
echo "=== SUMMARY ==="
echo "PASS: $PASS_COUNT   FAIL: $FAIL_COUNT   INFO: $INFO_COUNT"
echo ""
echo "--- Full results ---"
for r in "${RESULTS[@]}"; do
  echo "$r"
done

if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
exit 0
