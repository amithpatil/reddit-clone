#!/usr/bin/env bash
# Seeds a small dataset through the live REST API and verifies F8's moderation-dashboard surface end to end:
# the mod queue now carries a real content preview + author (previously a bare targetId), the new
# GET /mod/reports exposes individual report ids + reporter usernames (previously undiscoverable by anyone
# but the reporter), the new GET /mod/bans lists current bans with usernames (previously no list existed at
# all), GET /mod/join-requests now carries a username, all three automod rule types round-trip their config
# through the string/object seam, GET /r/{name}/about's new myPermissions matches the real bitmask exactly,
# and a capped moderator (PERM_REMOVE_CONTENT only) can use exactly what that bit allows and is 403'd from
# everything else. Prints PASS/FAIL with the real observed value for every check. Data is left in place.
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
  local uname="md${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

submit() {
  local token="$1" comm="$2" title="$3" key="$4"
  req POST "/r/$comm/submit" "{\"kind\":\"text\",\"title\":\"$title\",\"body\":\"body text\"}" \
    -H "Authorization: Bearer $token" -H "Idempotency-Key: $key"
  echo "$HTTP_BODY" | jq -r .id
}

################################################################################
echo "=== Phase A: seed owner, a capped moderator (PERM_REMOVE_CONTENT only), member, community ==="
################################################################################

OWNER=$(register owner)
LIMITED_MOD=$(register limitedmod)
MEMBER=$(register member)
LIMITED_MOD_ID=$(psql_c "SELECT id FROM users WHERE username='md${RUN}limitedmod'")
MEMBER_ID=$(psql_c "SELECT id FROM users WHERE username='md${RUN}member'")

COMM="mdcomm${RUN}"
req POST /r "{\"name\":\"$COMM\",\"description\":\"moderation dashboard seed\"}" -H "Authorization: Bearer $OWNER"
expect_status "create seed community" "200" "$HTTP_STATUS" "-"

req POST "/r/$COMM/mod/moderators" "{\"userId\":\"$LIMITED_MOD_ID\",\"permissions\":1}" -H "Authorization: Bearer $OWNER"
expect_status "owner adds a moderator capped to PERM_REMOVE_CONTENT (bit 1) only" "200" "$HTTP_STATUS" "-"

req POST "/r/$COMM/subscribe" "" -H "Authorization: Bearer $MEMBER"
expect_status "member subscribes" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase B: myPermissions on GET /about matches the real bitmask ==="
################################################################################

req GET "/r/$COMM/about" "" -H "Authorization: Bearer $LIMITED_MOD"
expect_eq "capped moderator's myPermissions is exactly 1" "1" "$(echo "$HTTP_BODY" | jq -r .myPermissions)"
req GET "/r/$COMM/about" "" -H "Authorization: Bearer $OWNER"
expect_eq "owner's myPermissions is Integer.MAX_VALUE" "2147483647" "$(echo "$HTTP_BODY" | jq -r .myPermissions)"
req GET "/r/$COMM/about" "" -H "Authorization: Bearer $MEMBER"
expect_eq "a non-moderator member's myPermissions is null" "null" "$(echo "$HTTP_BODY" | jq -r .myPermissions)"

################################################################################
echo "=== Phase C: mod queue gets a real preview/author; GET /mod/reports exposes report ids ==="
################################################################################

REPORTED_POST=$(submit "$MEMBER" "$COMM" "a post that will get reported" "md-post-${RUN}")
req POST /api/comment "{\"postId\":\"$REPORTED_POST\",\"body\":\"a comment that will get reported\"}" -H "Authorization: Bearer $MEMBER"
REPORTED_COMMENT=$(echo "$HTTP_BODY" | jq -r .id)

req POST /api/report "{\"targetType\":\"post\",\"targetId\":\"$REPORTED_POST\",\"reason\":\"spam\"}" -H "Authorization: Bearer $OWNER"
expect_status "owner files a report on the post" "200" "$HTTP_STATUS" "-"
req POST /api/report "{\"targetType\":\"comment\",\"targetId\":\"$REPORTED_COMMENT\",\"reason\":\"rude\"}" -H "Authorization: Bearer $OWNER"
expect_status "owner files a report on the comment" "200" "$HTTP_STATUS" "-"

req GET "/r/$COMM/mod/queue" "" -H "Authorization: Bearer $LIMITED_MOD"
expect_status "capped moderator can view the queue (any-mod-permission check)" "200" "$HTTP_STATUS" "-"
POST_PREVIEW=$(echo "$HTTP_BODY" | jq -r --arg id "$REPORTED_POST" '[.[] | select(.targetId == $id)][0].preview')
POST_AUTHOR=$(echo "$HTTP_BODY" | jq -r --arg id "$REPORTED_POST" '[.[] | select(.targetId == $id)][0].authorUsername')
expect_eq "queue entry's preview is the real post title" "a post that will get reported" "$POST_PREVIEW"
expect_eq "queue entry's authorUsername is correct" "md${RUN}member" "$POST_AUTHOR"
COMMENT_PREVIEW=$(echo "$HTTP_BODY" | jq -r --arg id "$REPORTED_COMMENT" '[.[] | select(.targetId == $id)][0].preview')
expect_eq "comment queue entry's preview is the real comment body" "a comment that will get reported" "$COMMENT_PREVIEW"

req GET "/r/$COMM/mod/reports?targetType=post&targetId=$REPORTED_POST" "" -H "Authorization: Bearer $LIMITED_MOD"
expect_status "capped moderator (PERM_REMOVE_CONTENT) can list individual reports" "200" "$HTTP_STATUS" "-"
REPORT_ID=$(echo "$HTTP_BODY" | jq -r '.[0].id')
REPORT_REASON=$(echo "$HTTP_BODY" | jq -r '.[0].reason')
REPORT_REPORTER=$(echo "$HTTP_BODY" | jq -r '.[0].reporterUsername')
expect_eq "the individual report carries the real reason" "spam" "$REPORT_REASON"
expect_eq "the individual report carries the real reporterUsername" "md${RUN}owner" "$REPORT_REPORTER"
if [ "$REPORT_ID" != "null" ] && [ -n "$REPORT_ID" ]; then
  record PASS "a real report id was discoverable from the queue, not just the reporter's own response" "id=$REPORT_ID"
else
  record FAIL "a real report id was discoverable from the queue, not just the reporter's own response" "id=$REPORT_ID"
fi

req POST "/r/$COMM/mod/reports/$REPORT_ID/resolve" "" -H "Authorization: Bearer $LIMITED_MOD"
expect_status "capped moderator resolves the discovered report" "200" "$HTTP_STATUS" "-"
RESOLVED_STATUS=$(psql_c "SELECT status FROM reports WHERE id = '$REPORT_ID'")
expect_eq "the report's status is now resolved" "resolved" "$RESOLVED_STATUS"

req POST "/r/$COMM/mod/remove/comment/$REPORTED_COMMENT" '{"reason":"handled via queue"}' -H "Authorization: Bearer $LIMITED_MOD"
expect_status "capped moderator (PERM_REMOVE_CONTENT) removes the reported comment" "200" "$HTTP_STATUS" "-"
COMMENT_REMOVED=$(psql_c "SELECT removed FROM comments WHERE id = '$REPORTED_COMMENT'")
expect_eq "the comment is actually removed" "t" "$COMMENT_REMOVED"

################################################################################
echo "=== Phase D: capped moderator is 403'd from everything PERM_REMOVE_CONTENT doesn't cover ==="
################################################################################

req POST "/r/$COMM/mod/ban" "{\"userId\":\"$MEMBER_ID\",\"reason\":\"should be forbidden\"}" -H "Authorization: Bearer $LIMITED_MOD"
expect_status "capped moderator cannot ban (no PERM_BAN_USERS)" "403" "$HTTP_STATUS" "-"
req GET "/r/$COMM/mod/bans" "" -H "Authorization: Bearer $LIMITED_MOD"
expect_status "capped moderator cannot even list bans (no PERM_BAN_USERS)" "403" "$HTTP_STATUS" "-"
req GET "/r/$COMM/mod/join-requests" "" -H "Authorization: Bearer $LIMITED_MOD"
expect_status "capped moderator cannot list join requests (no PERM_MANAGE_ACCESS)" "403" "$HTTP_STATUS" "-"
req POST "/r/$COMM/mod/automod-rules" '{"ruleType":"keyword","config":{"keywords":["x"]},"action":"report"}' -H "Authorization: Bearer $LIMITED_MOD"
expect_status "capped moderator cannot add an automod rule (no PERM_MANAGE_AUTOMOD)" "403" "$HTTP_STATUS" "-"
req GET "/r/$COMM/mod/automod-rules" "" -H "Authorization: Bearer $LIMITED_MOD"
expect_status "capped moderator CAN still view automod rules (any-mod-permission check)" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase E: bans list (new endpoint) carries usernames ==="
################################################################################

req POST "/r/$COMM/mod/ban" "{\"userId\":\"$MEMBER_ID\",\"reason\":\"testing the new list endpoint\"}" -H "Authorization: Bearer $OWNER"
expect_status "owner bans the member" "200" "$HTTP_STATUS" "-"

req GET "/r/$COMM/mod/bans" "" -H "Authorization: Bearer $OWNER"
expect_status "owner lists bans" "200" "$HTTP_STATUS" "-"
BAN_USERNAME=$(echo "$HTTP_BODY" | jq -r --arg id "$MEMBER_ID" '[.[] | select(.userId == $id)][0].username')
BAN_ISSUER=$(echo "$HTTP_BODY" | jq -r --arg id "$MEMBER_ID" '[.[] | select(.userId == $id)][0].issuerUsername')
expect_eq "the ban entry carries the banned user's real username" "md${RUN}member" "$BAN_USERNAME"
expect_eq "the ban entry carries the issuer's real username" "md${RUN}owner" "$BAN_ISSUER"

req DELETE "/r/$COMM/mod/ban/$MEMBER_ID" "" -H "Authorization: Bearer $OWNER"
expect_status "owner lifts the ban" "200" "$HTTP_STATUS" "-"
req GET "/r/$COMM/mod/bans" "" -H "Authorization: Bearer $OWNER"
BAN_STILL_LISTED=$(echo "$HTTP_BODY" | jq --arg id "$MEMBER_ID" '[.[] | select(.userId == $id)] | length > 0')
expect_eq "the lifted ban no longer appears in the list" "false" "$BAN_STILL_LISTED"

################################################################################
echo "=== Phase F: join-requests list carries a username ==="
################################################################################

OUTSIDER=$(register outsider)
PRIVCOMM="mdpriv${RUN}"
req POST /r "{\"name\":\"$PRIVCOMM\",\"description\":\"private seed\",\"type\":\"private\"}" -H "Authorization: Bearer $OWNER"
expect_status "create a private community" "200" "$HTTP_STATUS" "-"
req POST "/r/$PRIVCOMM/mod/moderators" "{\"userId\":\"$LIMITED_MOD_ID\",\"permissions\":1}" -H "Authorization: Bearer $OWNER"
expect_status "owner adds the same capped moderator to the private community" "200" "$HTTP_STATUS" "-"

req POST "/r/$PRIVCOMM/join-requests" "" -H "Authorization: Bearer $OUTSIDER"
expect_status "outsider requests to join the private community" "200" "$HTTP_STATUS" "-"

req GET "/r/$PRIVCOMM/mod/join-requests" "" -H "Authorization: Bearer $OWNER"
expect_status "owner lists join requests" "200" "$HTTP_STATUS" "-"
JOIN_USERNAME=$(echo "$HTTP_BODY" | jq -r '.[0].username')
expect_eq "the join request carries the outsider's real username" "md${RUN}outsider" "$JOIN_USERNAME"

req POST "/r/$PRIVCOMM/mod/join-requests/$(psql_c "SELECT id FROM users WHERE username='md${RUN}outsider'")/approve" "" -H "Authorization: Bearer $OWNER"
expect_status "owner approves the join request" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase G: all three automod rule types round-trip their config correctly ==="
################################################################################

req POST "/r/$COMM/mod/automod-rules" '{"ruleType":"keyword","config":{"keywords":["spamword","badword"]},"action":"remove"}' -H "Authorization: Bearer $OWNER"
expect_status "owner adds a keyword automod rule" "200" "$HTTP_STATUS" "-"
KEYWORD_RULE_ID=$(echo "$HTTP_BODY" | jq -r .id)

req POST "/r/$COMM/mod/automod-rules" '{"ruleType":"regex","config":{"pattern":"^spam.*"},"action":"report"}' -H "Authorization: Bearer $OWNER"
expect_status "owner adds a regex automod rule" "200" "$HTTP_STATUS" "-"
REGEX_RULE_ID=$(echo "$HTTP_BODY" | jq -r .id)

req POST "/r/$COMM/mod/automod-rules" '{"ruleType":"karma_threshold","config":{"minKarma":10},"action":"report"}' -H "Authorization: Bearer $OWNER"
expect_status "owner adds a karma_threshold automod rule" "200" "$HTTP_STATUS" "-"
KARMA_RULE_ID=$(echo "$HTTP_BODY" | jq -r .id)

req GET "/r/$COMM/mod/automod-rules" "" -H "Authorization: Bearer $OWNER"
KEYWORD_CONFIG=$(echo "$HTTP_BODY" | jq -r --arg id "$KEYWORD_RULE_ID" '[.[] | select(.id == $id)][0].config | fromjson | .keywords | join(",")')
expect_eq "the keyword rule's config round-trips its keyword list" "spamword,badword" "$KEYWORD_CONFIG"
REGEX_CONFIG=$(echo "$HTTP_BODY" | jq -r --arg id "$REGEX_RULE_ID" '[.[] | select(.id == $id)][0].config | fromjson | .pattern')
expect_eq "the regex rule's config round-trips its pattern" "^spam.*" "$REGEX_CONFIG"
KARMA_CONFIG=$(echo "$HTTP_BODY" | jq -r --arg id "$KARMA_RULE_ID" '[.[] | select(.id == $id)][0].config | fromjson | .minKarma')
expect_eq "the karma_threshold rule's config round-trips its minKarma" "10" "$KARMA_CONFIG"

req DELETE "/r/$COMM/mod/automod-rules/$REGEX_RULE_ID" "" -H "Authorization: Bearer $OWNER"
expect_status "owner deletes the regex rule" "200" "$HTTP_STATUS" "-"
req GET "/r/$COMM/mod/automod-rules" "" -H "Authorization: Bearer $OWNER"
REGEX_STILL_LISTED=$(echo "$HTTP_BODY" | jq --arg id "$REGEX_RULE_ID" '[.[] | select(.id == $id)] | length > 0')
expect_eq "the deleted rule no longer appears in the list" "false" "$REGEX_STILL_LISTED"

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
