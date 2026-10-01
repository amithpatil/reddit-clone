#!/usr/bin/env bash
# Seeds a post with four deliberately-crafted comments and re-verifies every comment sort option end to
# end: best (Wilson confidence, the default) picks the high-confidence/lower-raw-score comment over the
# high-raw-score/low-confidence one; top picks the opposite, proving the two sorts genuinely disagree, not
# coincidentally the same order; new/old correctly bracket a just-created zero-vote comment at opposite
# ends; controversial picks the split-vote comment; an invalid sort value is rejected. Prints PASS/FAIL
# with the real observed value for every check. Data is left in the dev database afterward.
#
# Prereqs: docker compose stack up, app running on $BASE.

set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
RUN=$(date +%s | tail -c 6)
PASSWORD="Sup3rSecret!1"
OUTBOX_WAIT=3   # OutboxWorker ticks every 2s

PASS_COUNT=0
FAIL_COUNT=0
declare -a RESULTS=()

record() {
  local status="$1" desc="$2" detail="$3"
  RESULTS+=("${status}|${desc}|${detail}")
  case "$status" in
    PASS) PASS_COUNT=$((PASS_COUNT+1)); printf 'PASS  %-70s %s\n' "$desc" "$detail" ;;
    FAIL) FAIL_COUNT=$((FAIL_COUNT+1)); printf 'FAIL  %-70s %s\n' "$desc" "$detail" ;;
    INFO) printf 'INFO  %-70s %s\n' "$desc" "$detail" ;;
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

expect_eq() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    record PASS "$desc" "expected $expected, got $actual"
  else
    record FAIL "$desc" "expected $expected, got $actual"
  fi
}

req() {
  local method="$1" path="$2" data="${3:-}"; shift 3 || true
  local resp
  resp=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" -H 'Content-Type: application/json' \
    ${data:+-d "$data"} "$@")
  HTTP_STATUS=$(echo "$resp" | tail -n1)
  HTTP_BODY=$(echo "$resp" | sed '$d')
}

register() {
  local uname="cs${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

vote() {
  local token="$1" targetId="$2" dir="$3"
  req POST /api/vote "{\"targetType\":\"comment\",\"targetId\":\"$targetId\",\"dir\":$dir}" -H "Authorization: Bearer $token"
}

################################################################################
echo "=== Phase A: seed author, post, and voters ==="
################################################################################

AUTHOR=$(register author)
# 50 voters so the Wilson-confidence example below (a tiny perfect-ratio sample vs. a large, barely-
# positive-ratio sample) has enough room to work with real numbers rather than a hand-waved claim.
VOTERS=()
for i in $(seq 0 49); do
  VOTERS+=("$(register voter$i)")
done

COMM="cscomm${RUN}"
req POST /r "{\"name\":\"$COMM\",\"description\":\"comment sort seed\"}" -H "Authorization: Bearer $AUTHOR"
expect_status "create seed community" "200" "$HTTP_STATUS" "-"

req POST "/r/$COMM/submit" "{\"kind\":\"text\",\"title\":\"seed post\",\"body\":\"seed body\"}" \
  -H "Authorization: Bearer $AUTHOR" -H "Idempotency-Key: cs-post-${RUN}"
POST_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "submit seed post" "200" "$HTTP_STATUS" "id=$POST_ID"

################################################################################
echo "=== Phase B: four comments with deliberately distinct engagement ==="
################################################################################

# High raw score, low confidence: 2 upvotes, 0 downvotes. score=2, n=2 -> bestRank ~=0.342 (tiny sample,
# capped confidence despite a perfect ratio).
req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"high score low confidence\"}" -H "Authorization: Bearer $AUTHOR"
HIGHSCORE_ID=$(echo "$HTTP_BODY" | jq -r .id)
vote "${VOTERS[0]}" "$HIGHSCORE_ID" 1
vote "${VOTERS[1]}" "$HIGHSCORE_ID" 1

# Lower raw score, higher confidence: 25 up, 24 down. score=1 (lower than the comment above), n=49,
# ratio~=0.510 -> bestRank ~=0.375 — Wilson's lower bound approaches the true ratio as n grows even when
# the ratio itself is only barely above 50%, so this large-but-barely-positive sample beats the tiny
# perfect-ratio one above despite a LOWER net score. Verified by hand against RankFormulas.bestRank before
# writing this script, not assumed.
req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"lower score higher confidence\"}" -H "Authorization: Bearer $AUTHOR"
HIGHCONF_ID=$(echo "$HTTP_BODY" | jq -r .id)
for v in $(seq 0 24); do vote "${VOTERS[$v]}" "$HIGHCONF_ID" 1; done
for v in $(seq 25 48); do vote "${VOTERS[$v]}" "$HIGHCONF_ID" -1; done

sleep "$OUTBOX_WAIT"

# Controversial: 25 up, 25 down (perfectly split, all 50 voters). score=0, controversialRank=50^(25/25)=50
# — clearly higher than the other two comments' controversialRank (0 and ~41.3), so this one should win
# sort=controversial outright.
req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"controversial split vote\"}" -H "Authorization: Bearer $AUTHOR"
CONTROVERSIAL_ID=$(echo "$HTTP_BODY" | jq -r .id)
for v in $(seq 0 24); do vote "${VOTERS[$v]}" "$CONTROVERSIAL_ID" 1; done
for v in $(seq 25 49); do vote "${VOTERS[$v]}" "$CONTROVERSIAL_ID" -1; done

sleep "$OUTBOX_WAIT"

# Freshly created, zero votes.
req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"brand new no votes\"}" -H "Authorization: Bearer $AUTHOR"
NEW_ID=$(echo "$HTTP_BODY" | jq -r .id)

sleep "$OUTBOX_WAIT"

################################################################################
echo "=== Phase C: sort=best (default) picks confidence over raw score ==="
################################################################################

req GET "/r/$COMM/comments/$POST_ID?sort=best" ""
BEST_FIRST=$(echo "$HTTP_BODY" | jq -r '.comments.data.children[0].data.id')
expect_eq "sort=best ranks the high-confidence comment first" "$HIGHCONF_ID" "$BEST_FIRST"

req GET "/r/$COMM/comments/$POST_ID" ""
DEFAULT_FIRST=$(echo "$HTTP_BODY" | jq -r '.comments.data.children[0].data.id')
expect_eq "omitting sort entirely defaults to best too" "$HIGHCONF_ID" "$DEFAULT_FIRST"

################################################################################
echo "=== Phase D: sort=top picks raw score, disagreeing with best ==="
################################################################################

req GET "/r/$COMM/comments/$POST_ID?sort=top" ""
TOP_FIRST=$(echo "$HTTP_BODY" | jq -r '.comments.data.children[0].data.id')
if [ "$TOP_FIRST" != "$HIGHCONF_ID" ]; then
  record PASS "sort=top disagrees with sort=best's first-place pick" "top first=$TOP_FIRST"
else
  record FAIL "sort=top disagrees with sort=best's first-place pick" "got the same comment as best: $TOP_FIRST"
fi

################################################################################
echo "=== Phase E: sort=new / sort=old bracket the zero-vote comment ==="
################################################################################

req GET "/r/$COMM/comments/$POST_ID?sort=new" ""
NEW_FIRST=$(echo "$HTTP_BODY" | jq -r '.comments.data.children[0].data.id')
expect_eq "sort=new ranks the just-created comment first" "$NEW_ID" "$NEW_FIRST"

req GET "/r/$COMM/comments/$POST_ID?sort=old" ""
OLD_LAST=$(echo "$HTTP_BODY" | jq -r '.comments.data.children[-1].data.id')
expect_eq "sort=old ranks the just-created comment last" "$NEW_ID" "$OLD_LAST"
OLD_FIRST=$(echo "$HTTP_BODY" | jq -r '.comments.data.children[0].data.id')
expect_eq "sort=old ranks the first-ever comment first" "$HIGHSCORE_ID" "$OLD_FIRST"

################################################################################
echo "=== Phase F: sort=controversial picks the split-vote comment ==="
################################################################################

req GET "/r/$COMM/comments/$POST_ID?sort=controversial" ""
CONTROVERSIAL_FIRST=$(echo "$HTTP_BODY" | jq -r '.comments.data.children[0].data.id')
expect_eq "sort=controversial ranks the split-vote comment first" "$CONTROVERSIAL_ID" "$CONTROVERSIAL_FIRST"

################################################################################
echo "=== Phase G: an invalid sort value is rejected ==="
################################################################################

req GET "/r/$COMM/comments/$POST_ID?sort=nonsense" ""
expect_status "an unrecognized sort value is rejected" "400" "$HTTP_STATUS" "-"

echo ""
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
