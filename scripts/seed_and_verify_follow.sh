#!/usr/bin/env bash
# Seeds a small dataset through the live REST API and verifies backend feature 10 (follow-a-user) end to
# end: follow/unfollow, idempotency of both, self-follow rejection, follower/following counts on both sides
# of the relationship, isFollowing resolution on GET /user/{username}/about (via the separate
# GET /user/{username}/follow status endpoint — auth has no dependency on follow, see
# ModuleBoundaryTest) and on GET /user/search (via POST /user/follow-status), followers/following list
# pagination and ordering, and the new_follower notification (created on follow, suppressed when muted).
# Prints PASS/FAIL with the real observed value for every check. Data is left in the dev database
# afterward.
#
# Prereqs: docker compose stack up, app running on $BASE.

set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
PASSWORD="Sup3rSecret!1"
RUN=$(date +%s | tail -c 6)

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
  local uname="fl${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

ALICE=$(register alice)
BOB=$(register bob)
CAROL=$(register carol)
ALICE_NAME="fl${RUN}alice"
BOB_NAME="fl${RUN}bob"
CAROL_NAME="fl${RUN}carol"

################################################################################
echo "=== Phase A: follow happy path + counts on both sides ==="
################################################################################

req GET "/user/$BOB_NAME/about" ""
BOB_FOLLOWERS_BEFORE=$(echo "$HTTP_BODY" | jq -r .followerCount)
expect_eq "bob starts with 0 followers" "0" "$BOB_FOLLOWERS_BEFORE"

req POST "/user/$BOB_NAME/follow" "" -H "Authorization: Bearer $ALICE"
expect_status "alice follows bob" "200" "$HTTP_STATUS" "-"

req GET "/user/$BOB_NAME/about" ""
expect_eq "bob's follower count is now 1" "1" "$(echo "$HTTP_BODY" | jq -r .followerCount)"

req GET "/user/$ALICE_NAME/about" ""
expect_eq "alice's following count is now 1" "1" "$(echo "$HTTP_BODY" | jq -r .followingCount)"

################################################################################
echo "=== Phase B: idempotency — double-follow and double-unfollow are no-ops ==="
################################################################################

req POST "/user/$BOB_NAME/follow" "" -H "Authorization: Bearer $ALICE"
expect_status "following bob again is still a 200, not an error" "200" "$HTTP_STATUS" "-"
req GET "/user/$BOB_NAME/about" ""
expect_eq "bob's follower count did not double-increment" "1" "$(echo "$HTTP_BODY" | jq -r .followerCount)"

req DELETE "/user/$BOB_NAME/follow" "" -H "Authorization: Bearer $ALICE"
expect_status "alice unfollows bob" "200" "$HTTP_STATUS" "-"
req DELETE "/user/$BOB_NAME/follow" "" -H "Authorization: Bearer $ALICE"
expect_status "unfollowing bob again is still a 200, not an error" "200" "$HTTP_STATUS" "-"
req GET "/user/$BOB_NAME/about" ""
expect_eq "bob's follower count is back to 0, not negative" "0" "$(echo "$HTTP_BODY" | jq -r .followerCount)"
req GET "/user/$ALICE_NAME/about" ""
expect_eq "alice's following count is back to 0" "0" "$(echo "$HTTP_BODY" | jq -r .followingCount)"

# Re-follow for the rest of the script.
req POST "/user/$BOB_NAME/follow" "" -H "Authorization: Bearer $ALICE"
expect_status "alice re-follows bob for the remaining checks" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase C: self-follow is rejected ==="
################################################################################

req POST "/user/$ALICE_NAME/follow" "" -H "Authorization: Bearer $ALICE"
expect_status "alice cannot follow herself" "400" "$HTTP_STATUS" "-"
req GET "/user/$ALICE_NAME/about" ""
expect_eq "alice's follower count is untouched by the rejected self-follow" "0" "$(echo "$HTTP_BODY" | jq -r .followerCount)"

################################################################################
echo "=== Phase D: isFollowing status — single (GET /user/{username}/follow) ==="
################################################################################

req GET "/user/$BOB_NAME/follow" "" -H "Authorization: Bearer $ALICE"
expect_status "alice checks her own follow status on bob" "200" "$HTTP_STATUS" "-"
expect_eq "alice is following bob" "true" "$(echo "$HTTP_BODY" | jq -r .isFollowing)"

req GET "/user/$BOB_NAME/follow" "" -H "Authorization: Bearer $CAROL"
expect_eq "carol is not following bob" "false" "$(echo "$HTTP_BODY" | jq -r .isFollowing)"

req GET "/user/$BOB_NAME/follow" ""
expect_status "the status endpoint requires auth (401 for an anonymous caller)" "401" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase E: isFollowing status — batch (POST /user/follow-status), used by Search ==="
################################################################################

carol_follows_bob_and_alice() {
  req POST "/user/$BOB_NAME/follow" "" -H "Authorization: Bearer $CAROL"
  req POST "/user/$ALICE_NAME/follow" "" -H "Authorization: Bearer $CAROL"
}
carol_follows_bob_and_alice

req POST "/user/follow-status" "[\"$BOB_NAME\",\"$ALICE_NAME\",\"nonexistent_user_${RUN}\"]" -H "Authorization: Bearer $CAROL"
expect_status "carol's batch follow-status call succeeds" "200" "$HTTP_STATUS" "-"
expect_eq "carol follows bob (batch)" "true" "$(echo "$HTTP_BODY" | jq -r --arg u "$BOB_NAME" '.[$u]')"
expect_eq "carol follows alice (batch)" "true" "$(echo "$HTTP_BODY" | jq -r --arg u "$ALICE_NAME" '.[$u]')"
expect_eq "a nonexistent username is silently omitted, not an error" "null" "$(echo "$HTTP_BODY" | jq -r --arg u "nonexistent_user_${RUN}" '.[$u]')"

################################################################################
echo "=== Phase F: followers/following list pagination and ordering ==="
################################################################################

req GET "/user/$BOB_NAME/followers" ""
expect_status "bob's followers list is public (no auth needed)" "200" "$HTTP_STATUS" "-"
BOB_FOLLOWER_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
expect_eq "bob has exactly 2 followers (alice, carol)" "2" "$BOB_FOLLOWER_COUNT"
BOB_FOLLOWER_NAMES=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.username' | sort)
EXPECTED_FOLLOWERS=$(printf '%s\n%s' "$ALICE_NAME" "$CAROL_NAME" | sort)
expect_eq "bob's followers are exactly alice and carol" "$EXPECTED_FOLLOWERS" "$BOB_FOLLOWER_NAMES"

req GET "/user/$CAROL_NAME/following" "" -H "Authorization: Bearer $ALICE"
CAROL_FOLLOWING_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
expect_eq "carol follows exactly 2 people (bob, alice)" "2" "$CAROL_FOLLOWING_COUNT"

# isFollowing attached on the list rows, resolved for the calling viewer (alice), not the list's subject.
ALICE_ROW_IS_FOLLOWING_SELF=$(echo "$HTTP_BODY" | jq -r --arg u "$ALICE_NAME" '.data.children[] | select(.data.username == $u) | .data.isFollowing')
expect_eq "viewing carol's following list as alice, alice's own row shows isFollowing=false (can't follow self)" "false" "$ALICE_ROW_IS_FOLLOWING_SELF"
BOB_ROW_IS_FOLLOWING=$(echo "$HTTP_BODY" | jq -r --arg u "$BOB_NAME" '.data.children[] | select(.data.username == $u) | .data.isFollowing')
expect_eq "alice (the viewer) does follow bob, reflected on his row in carol's following list" "true" "$BOB_ROW_IS_FOLLOWING"

# Pagination: register enough extra followers of bob to force a second page.
PAGE_SIZE=25
EXTRA=$((PAGE_SIZE - 1))
for i in $(seq 1 "$EXTRA"); do
  TOK=$(register "pg${i}")
  req POST "/user/$BOB_NAME/follow" "" -H "Authorization: Bearer $TOK"
done
req GET "/user/$BOB_NAME/followers" ""
PAGE1_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
expect_eq "bob's followers page 1 is capped at 25" "25" "$PAGE1_COUNT"
NEXT_CURSOR=$(echo "$HTTP_BODY" | jq -r '.data.after')
PAGE1_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id' | sort)

req GET "/user/$BOB_NAME/followers?after=$NEXT_CURSOR" ""
PAGE2_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
expect_eq "bob's followers page 2 has the remaining 1" "1" "$PAGE2_COUNT"
PAGE2_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id' | sort)

OVERLAP=$(comm -12 <(echo "$PAGE1_IDS") <(echo "$PAGE2_IDS") | wc -l | tr -d ' ')
expect_eq "no overlap between page 1 and page 2" "0" "$OVERLAP"
TOTAL_DISTINCT=$(printf '%s\n%s' "$PAGE1_IDS" "$PAGE2_IDS" | sort -u | wc -l | tr -d ' ')
expect_eq "pages 1+2 together account for all 26 followers, no gaps" "26" "$TOTAL_DISTINCT"

################################################################################
echo "=== Phase G: new_follower notification, and its mute preference ==="
################################################################################

DAVE=$(register dave)
DAVE_NAME="fl${RUN}dave"

req POST "/user/$DAVE_NAME/follow" "" -H "Authorization: Bearer $ALICE"
expect_status "alice follows dave" "200" "$HTTP_STATUS" "-"
sleep 2.5 # NotificationOutboxWorker polls every 2s

req GET "/api/notifications" "" -H "Authorization: Bearer $DAVE"
DAVE_NOTIF_COUNT=$(echo "$HTTP_BODY" | jq '[.[] | select(.type == "new_follower")] | length')
expect_eq "dave has exactly one new_follower notification" "1" "$DAVE_NOTIF_COUNT"
DAVE_NOTIF_ACTOR=$(echo "$HTTP_BODY" | jq -r '[.[] | select(.type == "new_follower")][0].actorUsername')
expect_eq "the notification's actor is alice" "$ALICE_NAME" "$DAVE_NOTIF_ACTOR"

# Mute new_follower, then have carol follow dave too — should produce no new notification.
req PATCH "/api/v1/me/prefs" '{"notificationPrefs":{"new_follower":false}}' -H "Authorization: Bearer $DAVE"
expect_status "dave mutes new_follower notifications" "200" "$HTTP_STATUS" "-"

req POST "/user/$DAVE_NAME/follow" "" -H "Authorization: Bearer $CAROL"
expect_status "carol follows dave" "200" "$HTTP_STATUS" "-"
sleep 2.5

req GET "/api/notifications" "" -H "Authorization: Bearer $DAVE"
DAVE_NOTIF_COUNT_AFTER_MUTE=$(echo "$HTTP_BODY" | jq '[.[] | select(.type == "new_follower")] | length')
expect_eq "muting suppressed the second new_follower notification (still just 1)" "1" "$DAVE_NOTIF_COUNT_AFTER_MUTE"

################################################################################
echo
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
