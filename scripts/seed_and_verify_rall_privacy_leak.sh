#!/usr/bin/env bash
# Seeds a small dataset through the live REST API and re-verifies the private-community leak fix across
# every "r/all" sitewide post listing (new/hot/top/rising/controversial) plus the public /user/{username}
# submitted and comments tabs: a private community's post/comment used to appear in these cross-community
# listings for any viewer, including anonymous ones, even though opening the post itself was already
# correctly blocked by requireViewAccess. Each of these now excludes a private community's content unless
# the viewer is a member or moderator of that specific community. Also flushes the /hot first-page Redis
# cache before the anonymous /r/all/hot check, so this test isn't at the mercy of the existing 45s TTL.
# Prints PASS/FAIL with the real observed value for every check. Data is left in the dev database
# afterward.
#
# Prereqs: docker compose stack up, app running on $BASE.

set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5434}"
PGUSER="${PGUSER:-app}"
PGDATABASE="${PGDATABASE:-redditclone}"
export PGPASSWORD="${PGPASSWORD:-devpassword}"

RUN=$(date +%s | tail -c 6)
PASSWORD="Sup3rSecret!1"

PASS_COUNT=0
FAIL_COUNT=0
declare -a RESULTS=()

psql_c() { psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -t -A -c "$1"; }

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
  local uname="rl${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

submit() {
  local token="$1" comm="$2" title="$3" key="$4"
  req POST "/r/$comm/submit" "{\"kind\":\"text\",\"title\":\"$title\",\"body\":\"body\"}" \
    -H "Authorization: Bearer $token" -H "Idempotency-Key: $key"
}

# ids_contain <jq-array-filter-on-HTTP_BODY> <needle-id> -> "true"/"false"
ids_from_listing() { echo "$HTTP_BODY" | jq -r '.data.children[].data.id'; }

################################################################################
echo "=== Phase A: seed owner/member/outsider and a public + private community ==="
################################################################################

OWNER=$(register owner)
MEMBER=$(register member)
OUTSIDER=$(register outsider)
OWNER_ID=$(psql_c "SELECT id FROM users WHERE username='rl${RUN}owner'")
MEMBER_ID=$(psql_c "SELECT id FROM users WHERE username='rl${RUN}member'")

PUBCOMM="rlpub${RUN}"
req POST /r "{\"name\":\"$PUBCOMM\",\"description\":\"public seed\",\"type\":\"public\"}" -H "Authorization: Bearer $OWNER"
expect_status "create the public community" "200" "$HTTP_STATUS" "-"

PRIVCOMM="rlpriv${RUN}"
req POST /r "{\"name\":\"$PRIVCOMM\",\"description\":\"private seed\",\"type\":\"private\"}" -H "Authorization: Bearer $OWNER"
expect_status "create the private community" "200" "$HTTP_STATUS" "-"

req POST "/r/$PRIVCOMM/join-requests" "" -H "Authorization: Bearer $MEMBER"
expect_status "member requests to join the private community" "200" "$HTTP_STATUS" "-"
req POST "/r/$PRIVCOMM/mod/join-requests/$MEMBER_ID/approve" "" -H "Authorization: Bearer $OWNER"
expect_status "owner approves the member's join request" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase B: one post in each community, plus a comment on the private post ==="
################################################################################

submit "$OWNER" "$PUBCOMM" "rall leak check public post ${RUN}" "rl-pub-${RUN}"
expect_status "owner posts in the public community" "200" "$HTTP_STATUS" "-"
PUB_POST_ID=$(echo "$HTTP_BODY" | jq -r .id)

submit "$OWNER" "$PRIVCOMM" "rall leak check private post ${RUN}" "rl-priv-${RUN}"
expect_status "owner posts in the private community" "200" "$HTTP_STATUS" "-"
PRIV_POST_ID=$(echo "$HTTP_BODY" | jq -r .id)

req POST /api/comment "{\"postId\":\"$PRIV_POST_ID\",\"body\":\"member comment on the private post\"}" -H "Authorization: Bearer $MEMBER"
expect_status "member comments on the private post" "200" "$HTTP_STATUS" "-"
PRIV_COMMENT_ID=$(echo "$HTTP_BODY" | jq -r .id)

# This dev DB has accumulated thousands of posts across the whole testing history, some with real
# score/rank from real votes. /top, /rising, /controversial only return the top 25 by rank — a brand-new,
# never-voted post (score/risingRank/controversialRank all 0) would legitimately not make page 1 against
# that much history, which would make Phase C's /top /rising /controversial checks fail for a reason that
# has nothing to do with the privacy filter under test. Boost both seeded posts' rank columns directly
# (ranking data, not content — both rows were created through the real submit endpoint above) so they're
# guaranteed to win page 1 regardless of how much other data exists, the same spirit as this suite's
# existing precedent of directly manipulating rank columns to make a check deterministic (see
# seed_and_verify_phase2.sh's RankDecayJob simulation).
psql_c "UPDATE posts SET score = 999999, rising_rank = 999999, controversial_rank = 999999 WHERE id IN ('$PUB_POST_ID', '$PRIV_POST_ID')" > /dev/null

################################################################################
echo "=== Phase C: every /r/all/* sort excludes the private post for anonymous/outsider, includes it for member/owner ==="
################################################################################

for sort in new top rising controversial; do
  req GET "/r/all/$sort" ""
  expect_status "anonymous GET /r/all/$sort still works" "200" "$HTTP_STATUS" "-"
  ANON_IDS=$(ids_from_listing)
  if echo "$ANON_IDS" | grep -qx "$PRIV_POST_ID"; then
    record FAIL "anonymous /r/all/$sort does not leak the private post" "found $PRIV_POST_ID in results"
  else
    record PASS "anonymous /r/all/$sort does not leak the private post" "not found, as expected"
  fi

  req GET "/r/all/$sort" "" -H "Authorization: Bearer $OUTSIDER"
  OUTSIDER_IDS=$(ids_from_listing)
  if echo "$OUTSIDER_IDS" | grep -qx "$PRIV_POST_ID"; then
    record FAIL "non-member /r/all/$sort does not leak the private post" "found $PRIV_POST_ID in results"
  else
    record PASS "non-member /r/all/$sort does not leak the private post" "not found, as expected"
  fi

  req GET "/r/all/$sort" "" -H "Authorization: Bearer $MEMBER"
  MEMBER_IDS=$(ids_from_listing)
  if echo "$MEMBER_IDS" | grep -qx "$PRIV_POST_ID"; then
    record PASS "member /r/all/$sort still shows the private post" "found $PRIV_POST_ID, as expected"
  else
    record FAIL "member /r/all/$sort still shows the private post" "not found — should be visible to a member"
  fi

  req GET "/r/all/$sort" "" -H "Authorization: Bearer $OWNER"
  OWNER_IDS=$(ids_from_listing)
  if echo "$OWNER_IDS" | grep -qx "$PRIV_POST_ID"; then
    record PASS "owner/moderator /r/all/$sort still shows the private post" "found $PRIV_POST_ID, as expected"
  else
    record FAIL "owner/moderator /r/all/$sort still shows the private post" "not found — should be visible to a moderator"
  fi
done

# /hot is special: cached per the FeedCacheService 45s TTL, but only for anonymous (viewerId == null)
# requests. Flush that one key first so this check isn't at the mercy of a stale pre-fix cache entry from
# earlier testing — same key FeedCacheService.key("all") computes.
docker compose exec -T redis redis-cli DEL feed:hot:all > /dev/null 2>&1

req GET "/r/all/hot" ""
expect_status "anonymous GET /r/all/hot still works (cache flushed first)" "200" "$HTTP_STATUS" "-"
ANON_HOT_IDS=$(ids_from_listing)
if echo "$ANON_HOT_IDS" | grep -qx "$PRIV_POST_ID"; then
  record FAIL "anonymous /r/all/hot (freshly cached) does not leak the private post" "found $PRIV_POST_ID in results"
else
  record PASS "anonymous /r/all/hot (freshly cached) does not leak the private post" "not found, as expected"
fi

req GET "/r/all/hot" "" -H "Authorization: Bearer $OUTSIDER"
OUTSIDER_HOT_IDS=$(ids_from_listing)
if echo "$OUTSIDER_HOT_IDS" | grep -qx "$PRIV_POST_ID"; then
  record FAIL "non-member /r/all/hot (uncached, authenticated) does not leak the private post" "found $PRIV_POST_ID"
else
  record PASS "non-member /r/all/hot (uncached, authenticated) does not leak the private post" "not found, as expected"
fi

req GET "/r/all/hot" "" -H "Authorization: Bearer $MEMBER"
MEMBER_HOT_IDS=$(ids_from_listing)
if echo "$MEMBER_HOT_IDS" | grep -qx "$PRIV_POST_ID"; then
  record PASS "member /r/all/hot still shows the private post" "found $PRIV_POST_ID, as expected"
else
  record FAIL "member /r/all/hot still shows the private post" "not found — should be visible to a member"
fi

################################################################################
echo "=== Phase D: GET /user/{owner}/submitted excludes/includes the private post the same way ==="
################################################################################

req GET "/user/rl${RUN}owner/submitted" ""
expect_status "anonymous GET submitted tab still works" "200" "$HTTP_STATUS" "-"
ANON_SUB_IDS=$(ids_from_listing)
if echo "$ANON_SUB_IDS" | grep -qx "$PRIV_POST_ID"; then
  record FAIL "anonymous submitted-tab view does not leak the private post" "found $PRIV_POST_ID"
else
  record PASS "anonymous submitted-tab view does not leak the private post" "not found, as expected"
fi
if echo "$ANON_SUB_IDS" | grep -qx "$PUB_POST_ID"; then
  record PASS "anonymous submitted-tab view still shows the public post" "found $PUB_POST_ID, as expected"
else
  record FAIL "anonymous submitted-tab view still shows the public post" "not found — regression"
fi

req GET "/user/rl${RUN}owner/submitted" "" -H "Authorization: Bearer $OUTSIDER"
OUTSIDER_SUB_IDS=$(ids_from_listing)
if echo "$OUTSIDER_SUB_IDS" | grep -qx "$PRIV_POST_ID"; then
  record FAIL "non-member submitted-tab view does not leak the private post" "found $PRIV_POST_ID"
else
  record PASS "non-member submitted-tab view does not leak the private post" "not found, as expected"
fi

req GET "/user/rl${RUN}owner/submitted" "" -H "Authorization: Bearer $MEMBER"
MEMBER_SUB_IDS=$(ids_from_listing)
if echo "$MEMBER_SUB_IDS" | grep -qx "$PRIV_POST_ID"; then
  record PASS "member viewing owner's submitted tab still sees the private post" "found $PRIV_POST_ID, as expected"
else
  record FAIL "member viewing owner's submitted tab still sees the private post" "not found — should be visible"
fi

################################################################################
echo "=== Phase E: GET /user/{member}/comments excludes/includes the private comment the same way ==="
################################################################################

req GET "/user/rl${RUN}member/comments" ""
expect_status "anonymous GET comments tab still works" "200" "$HTTP_STATUS" "-"
ANON_COM_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id')
if echo "$ANON_COM_IDS" | grep -qx "$PRIV_COMMENT_ID"; then
  record FAIL "anonymous comments-tab view does not leak the private comment" "found $PRIV_COMMENT_ID"
else
  record PASS "anonymous comments-tab view does not leak the private comment" "not found, as expected"
fi

req GET "/user/rl${RUN}member/comments" "" -H "Authorization: Bearer $OUTSIDER"
OUTSIDER_COM_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id')
if echo "$OUTSIDER_COM_IDS" | grep -qx "$PRIV_COMMENT_ID"; then
  record FAIL "non-member comments-tab view does not leak the private comment" "found $PRIV_COMMENT_ID"
else
  record PASS "non-member comments-tab view does not leak the private comment" "not found, as expected"
fi

req GET "/user/rl${RUN}member/comments" "" -H "Authorization: Bearer $OWNER"
OWNER_COM_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id')
if echo "$OWNER_COM_IDS" | grep -qx "$PRIV_COMMENT_ID"; then
  record PASS "owner/moderator viewing member's comments tab still sees the private comment" "found $PRIV_COMMENT_ID, as expected"
else
  record FAIL "owner/moderator viewing member's comments tab still sees the private comment" "not found — should be visible"
fi

################################################################################
echo "=== Phase F: regression — the already-public /r/{name}/pinned stays unaffected (not part of this fix) ==="
################################################################################

req GET "/r/$PRIVCOMM/pinned" ""
expect_status "anonymous GET pinned on a private community is still 200 (unchanged, by design)" "200" "$HTTP_STATUS" "-"

################################################################################
echo
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
