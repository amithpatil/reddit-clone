#!/usr/bin/env bash
# Seeds a small dataset through the live REST API and verifies F7's three new/changed surfaces end to end:
# GET /user/{username}/submitted and /user/{username}/comments (keyset-paginated, scoped strictly to that
# one author, excluding removed content, 404 on an unknown username, reachable unauthenticated), and
# GET /user/{username}/about now carrying `status` (active by default, "deleted" after self-service account
# deletion). Prints PASS/FAIL with the real observed value for every check. Data is left in place.
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
  local uname="up${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

submit() {
  local token="$1" comm="$2" title="$3" key="$4"
  req POST "/r/$comm/submit" "{\"kind\":\"text\",\"title\":\"$title\",\"body\":\"body\"}" \
    -H "Authorization: Bearer $token" -H "Idempotency-Key: $key"
  echo "$HTTP_BODY" | jq -r .id
}

comment() {
  local token="$1" postId="$2" body="$3"
  req POST /api/comment "{\"postId\":\"$postId\",\"body\":\"$body\"}" -H "Authorization: Bearer $token"
  echo "$HTTP_BODY" | jq -r .id
}

################################################################################
echo "=== Phase A: seed owner (mod), profile user, noise user, community ==="
################################################################################

OWNER=$(register owner)
PROFILE=$(register profile)
OTHER=$(register other)
PROFILE_UNAME="up${RUN}profile"
OWNER_UNAME="up${RUN}owner"

COMM="upcomm${RUN}"
req POST /r "{\"name\":\"$COMM\",\"description\":\"user profile seed\"}" -H "Authorization: Bearer $OWNER"
expect_status "create seed community" "200" "$HTTP_STATUS" "-"

req POST "/r/$COMM/subscribe" "" -H "Authorization: Bearer $PROFILE"
expect_status "profile user subscribes" "200" "$HTTP_STATUS" "-"
req POST "/r/$COMM/subscribe" "" -H "Authorization: Bearer $OTHER"
expect_status "noise user subscribes" "200" "$HTTP_STATUS" "-"

ANCHOR_POST=$(submit "$OWNER" "$COMM" "anchor post for comments" "up-anchor-${RUN}")
expect_status "owner creates the anchor post" "200" "$HTTP_STATUS" "id=$ANCHOR_POST"

################################################################################
echo "=== Phase B: seed 27 posts + 27 comments by the profile user (forces 2 pages at PAGE_SIZE=25) ==="
################################################################################

PROFILE_POSTS=()
for i in $(seq 1 27); do
  PROFILE_POSTS+=("$(submit "$PROFILE" "$COMM" "profile post $i" "up-p-$i-${RUN}")")
done
record INFO "seeded profile posts" "count=${#PROFILE_POSTS[@]}"

PROFILE_COMMENTS=()
for i in $(seq 1 27); do
  PROFILE_COMMENTS+=("$(comment "$PROFILE" "$ANCHOR_POST" "profile comment $i")")
done
record INFO "seeded profile comments" "count=${#PROFILE_COMMENTS[@]}"

# Noise from an unrelated user — must never appear in the profile user's listings.
OTHER_POST=$(submit "$OTHER" "$COMM" "other user's post" "up-other-post-${RUN}")
OTHER_COMMENT=$(comment "$OTHER" "$ANCHOR_POST" "other user's comment")

# Remove one post and one comment via moderation — both must disappear from the listings.
REMOVED_POST="${PROFILE_POSTS[0]}"
REMOVED_COMMENT="${PROFILE_COMMENTS[0]}"
req POST "/r/$COMM/mod/remove/post/$REMOVED_POST" "" -H "Authorization: Bearer $OWNER"
expect_status "owner removes one profile post" "200" "$HTTP_STATUS" "id=$REMOVED_POST"
req POST "/r/$COMM/mod/remove/comment/$REMOVED_COMMENT" "" -H "Authorization: Bearer $OWNER"
expect_status "owner removes one profile comment" "200" "$HTTP_STATUS" "id=$REMOVED_COMMENT"

################################################################################
echo "=== Phase C: GET /user/{username}/submitted ==="
################################################################################

req GET "/user/$PROFILE_UNAME/submitted" ""
expect_status "submitted tab reachable unauthenticated" "200" "$HTTP_STATUS" "-"
PAGE1_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id')
PAGE1_COUNT=$(echo "$PAGE1_IDS" | grep -c .)
AFTER1=$(echo "$HTTP_BODY" | jq -r '.data.after')
expect_eq "submitted page 1 has PAGE_SIZE=25 items" "25" "$PAGE1_COUNT"
if [ "$AFTER1" != "null" ] && [ -n "$AFTER1" ]; then
  record PASS "submitted page 1 has a next cursor" "after=$AFTER1"
else
  record FAIL "submitted page 1 has a next cursor" "after=$AFTER1"
fi

req GET "/user/$PROFILE_UNAME/submitted?after=$AFTER1" ""
expect_status "submitted page 2 reachable" "200" "$HTTP_STATUS" "-"
PAGE2_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id')
PAGE2_COUNT=$(echo "$PAGE2_IDS" | grep -c .)
AFTER2=$(echo "$HTTP_BODY" | jq -r '.data.after')
# 27 seeded - 1 removed = 26 surviving; 25 on page 1, 1 on page 2.
expect_eq "submitted page 2 has the remaining 1 item" "1" "$PAGE2_COUNT"

# A non-empty page always carries a cursor in this codebase's pagination convention (see
# PostController.listNew) — termination is only signaled by an actually empty page, not by the last
# partial page's own cursor. Confirm that empty-page termination, not that page 2's own cursor is null.
req GET "/user/$PROFILE_UNAME/submitted?after=$AFTER2" ""
PAGE3_COUNT=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id' | grep -c .)
AFTER3=$(echo "$HTTP_BODY" | jq -r '.data.after')
expect_eq "submitted page 3 is empty (true end of pagination)" "0" "$PAGE3_COUNT"
expect_eq "submitted page 3 carries no further cursor" "null" "$AFTER3"

ALL_SUBMITTED_IDS=$(printf '%s\n%s' "$PAGE1_IDS" "$PAGE2_IDS")
ALL_AUTHORS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.authorUsername')
if echo "$ALL_SUBMITTED_IDS" | grep -qx "$REMOVED_POST"; then
  record FAIL "removed post excluded from submitted" "found $REMOVED_POST"
else
  record PASS "removed post excluded from submitted" "not present"
fi
if echo "$ALL_SUBMITTED_IDS" | grep -qx "$OTHER_POST"; then
  record FAIL "other user's post never appears in profile user's submitted" "found $OTHER_POST"
else
  record PASS "other user's post never appears in profile user's submitted" "not present"
fi

################################################################################
echo "=== Phase D: GET /user/{username}/comments ==="
################################################################################

req GET "/user/$PROFILE_UNAME/comments" ""
expect_status "comments tab reachable unauthenticated" "200" "$HTTP_STATUS" "-"
CPAGE1_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id')
CPAGE1_COUNT=$(echo "$CPAGE1_IDS" | grep -c .)
CAFTER1=$(echo "$HTTP_BODY" | jq -r '.data.after')
expect_eq "comments page 1 has PAGE_SIZE=25 items" "25" "$CPAGE1_COUNT"
SAMPLE_POST_TITLE=$(echo "$HTTP_BODY" | jq -r '.data.children[0].data.postTitle')
SAMPLE_COMM_NAME=$(echo "$HTTP_BODY" | jq -r '.data.children[0].data.communityName')
expect_eq "a comment carries its post's title" "anchor post for comments" "$SAMPLE_POST_TITLE"
expect_eq "a comment carries its post's community name" "$COMM" "$SAMPLE_COMM_NAME"

req GET "/user/$PROFILE_UNAME/comments?after=$CAFTER1" ""
CPAGE2_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id')
CPAGE2_COUNT=$(echo "$CPAGE2_IDS" | grep -c .)
CAFTER2=$(echo "$HTTP_BODY" | jq -r '.data.after')
expect_eq "comments page 2 has the remaining 1 item" "1" "$CPAGE2_COUNT"

req GET "/user/$PROFILE_UNAME/comments?after=$CAFTER2" ""
CPAGE3_COUNT=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id' | grep -c .)
CAFTER3=$(echo "$HTTP_BODY" | jq -r '.data.after')
expect_eq "comments page 3 is empty (true end of pagination)" "0" "$CPAGE3_COUNT"
expect_eq "comments page 3 carries no further cursor" "null" "$CAFTER3"

ALL_COMMENT_IDS=$(printf '%s\n%s' "$CPAGE1_IDS" "$CPAGE2_IDS")
if echo "$ALL_COMMENT_IDS" | grep -qx "$REMOVED_COMMENT"; then
  record FAIL "removed comment excluded from comments tab" "found $REMOVED_COMMENT"
else
  record PASS "removed comment excluded from comments tab" "not present"
fi
if echo "$ALL_COMMENT_IDS" | grep -qx "$OTHER_COMMENT"; then
  record FAIL "other user's comment never appears in profile user's comments" "found $OTHER_COMMENT"
else
  record PASS "other user's comment never appears in profile user's comments" "not present"
fi

################################################################################
echo "=== Phase E: unknown username 404s on all three endpoints ==="
################################################################################

UNKNOWN="up${RUN}doesnotexist"
req GET "/user/$UNKNOWN/submitted" ""
expect_status "unknown username 404s on submitted" "404" "$HTTP_STATUS" "-"
req GET "/user/$UNKNOWN/comments" ""
expect_status "unknown username 404s on comments" "404" "$HTTP_STATUS" "-"
req GET "/user/$UNKNOWN/about" ""
expect_status "unknown username 404s on about" "404" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase F: /user/{username}/about now carries status ==="
################################################################################

req GET "/user/$OWNER_UNAME/about" ""
expect_status "about reachable unauthenticated" "200" "$HTTP_STATUS" "-"
expect_eq "an active account's about.status" "active" "$(echo "$HTTP_BODY" | jq -r .status)"

DELETE_TOKEN=$(register deleteme)
DELETE_UNAME="up${RUN}deleteme"
DELETE_USER_ID=$(psql_c "SELECT id FROM users WHERE username='$DELETE_UNAME'")
req DELETE /api/v1/me "{\"password\":\"$PASSWORD\"}" -H "Authorization: Bearer $DELETE_TOKEN"
expect_status "self-service account deletion succeeds" "200" "$HTTP_STATUS" "-"
# deleteAccount() renames the username to deleted_<id> (anonymization) — the original username no longer
# resolves to anything after this.
req GET "/user/deleted_$DELETE_USER_ID/about" ""
expect_eq "a deleted account's about.status" "deleted" "$(echo "$HTTP_BODY" | jq -r .status)"

################################################################################
echo "=== Results ==="
################################################################################
echo "PASS: $PASS_COUNT  FAIL: $FAIL_COUNT"
if [ "$FAIL_COUNT" -gt 0 ]; then
  echo "--- failures ---"
  for r in "${RESULTS[@]}"; do
    IFS='|' read -r status desc detail <<< "$r"
    [ "$status" = "FAIL" ] && echo "FAIL: $desc — $detail"
  done
  exit 1
fi
