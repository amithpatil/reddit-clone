#!/usr/bin/env bash
# Seeds a small dataset through the live Phase 3 REST API and re-verifies moderation, automod, search,
# and account-level bans end to end: keyword-rule automod auto-removal at submission time, an automod
# "report" action populating the mod queue instead of removing, automod rule deletion actually stopping
# future matches, user-filed reports (resolve/dismiss), manual content removal for both a post and a
# comment, the moderation-action audit log, a capped-permission moderator (only PERM_REMOVE_CONTENT) who
# can remove content but is correctly forbidden from banning, community ban/unban (banned user blocked
# from submitting and commenting, unblocked after lifting), mod-mail mute/unmute (muted user blocked from
# sending mod mail, unblocked after unmuting), community-scoped search, and site-wide (account-level) admin
# ban/unban (banned user's login rejected, restored after unban). Prints PASS/FAIL with the real observed
# value for every check. Data is left in the dev database afterward.
#
# Prereqs: docker compose stack up, app running on $BASE.

set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5434}"
PGUSER="${PGUSER:-app}"
PGDATABASE="${PGDATABASE:-redditclone}"
export PGPASSWORD="${PGPASSWORD:-devpassword}"

RUN=$(date +%s | tail -c 6)   # short run-scoped suffix so re-runs never collide with prior seed data
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
  local uname="p3${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

################################################################################
echo "=== Phase A: seed owner, limited moderator, regular user, site admin, community ==="
################################################################################

OWNER=$(register owner)
LIMITED_MOD=$(register limitedmod)
MEMBER=$(register member)
ADMIN=$(register admin)
OWNER_ID=$(psql_c "SELECT id FROM users WHERE username='p3${RUN}owner'")
LIMITED_MOD_ID=$(psql_c "SELECT id FROM users WHERE username='p3${RUN}limitedmod'")
MEMBER_ID=$(psql_c "SELECT id FROM users WHERE username='p3${RUN}member'")
psql_c "UPDATE users SET is_site_admin = true WHERE username = 'p3${RUN}admin'" > /dev/null

COMM="p3comm${RUN}"
req POST /r "{\"name\":\"$COMM\",\"description\":\"phase 3 seed\"}" -H "Authorization: Bearer $OWNER"
expect_status "create seed community (owner gets OWNER_PERMISSIONS)" "200" "$HTTP_STATUS" "body=$HTTP_BODY"

req POST "/r/$COMM/subscribe" "" -H "Authorization: Bearer $MEMBER"
expect_status "member subscribes" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase B: moderator management (capped permission grant) ==="
################################################################################

# PERM_REMOVE_CONTENT = 1 — a moderator who can remove content but nothing else.
req POST "/r/$COMM/mod/moderators" "{\"userId\":\"$LIMITED_MOD_ID\",\"permissions\":1}" -H "Authorization: Bearer $OWNER"
expect_status "owner adds a moderator capped to PERM_REMOVE_CONTENT only" "200" "$HTTP_STATUS" "-"

req POST "/r/$COMM/mod/moderators" "{\"userId\":\"$MEMBER_ID\",\"permissions\":2147483647}" -H "Authorization: Bearer $LIMITED_MOD"
expect_status "capped moderator cannot grant permissions beyond their own" "403" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase C: automod — keyword auto-remove, keyword auto-report, rule deletion ==="
################################################################################

req POST "/r/$COMM/mod/automod-rules" '{"ruleType":"keyword","config":{"keywords":["spamword"]},"action":"remove"}' \
  -H "Authorization: Bearer $OWNER"
REMOVE_RULE_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "add keyword automod rule (action=remove)" "200" "$HTTP_STATUS" "id=$REMOVE_RULE_ID"

req POST "/r/$COMM/mod/automod-rules" '{"ruleType":"keyword","config":{"keywords":["reportword"]},"action":"report"}' \
  -H "Authorization: Bearer $OWNER"
expect_status "add keyword automod rule (action=report)" "200" "$HTTP_STATUS" "-"

req GET "/r/$COMM/mod/automod-rules" "" -H "Authorization: Bearer $OWNER"
RULE_COUNT=$(echo "$HTTP_BODY" | jq 'length')
expect_eq "listing automod rules shows both" "2" "$RULE_COUNT"

req POST "/r/$COMM/submit" '{"kind":"text","title":"buy now","body":"this contains spamword for sure"}' \
  -H "Authorization: Bearer $MEMBER" -H "Idempotency-Key: p3-spam-${RUN}"
SPAM_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "member submits a post matching the remove-rule" "200" "$HTTP_STATUS" "id=$SPAM_ID"
SPAM_REMOVED=$(psql_c "SELECT removed FROM posts WHERE id = '$SPAM_ID'")
expect_eq "automod auto-removed the spam post at submission time" "t" "$SPAM_REMOVED"

req POST "/r/$COMM/submit" '{"kind":"text","title":"hmm","body":"this contains reportword instead"}' \
  -H "Authorization: Bearer $MEMBER" -H "Idempotency-Key: p3-report-${RUN}"
REPORTWORD_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "member submits a post matching the report-rule" "200" "$HTTP_STATUS" "id=$REPORTWORD_ID"
REPORTWORD_REMOVED=$(psql_c "SELECT removed FROM posts WHERE id = '$REPORTWORD_ID'")
expect_eq "report-rule match is NOT removed, only queued" "f" "$REPORTWORD_REMOVED"

req GET "/r/$COMM/mod/queue" "" -H "Authorization: Bearer $OWNER"
QUEUE_HAS_REPORTWORD=$(echo "$HTTP_BODY" | jq --arg id "$REPORTWORD_ID" '[.[] | select(.targetId == $id)] | length > 0')
expect_eq "automod report-action match appears in the mod queue" "true" "$QUEUE_HAS_REPORTWORD"

req DELETE "/r/$COMM/mod/automod-rules/$REMOVE_RULE_ID" "" -H "Authorization: Bearer $OWNER"
expect_status "delete the remove-rule" "200" "$HTTP_STATUS" "-"
req POST "/r/$COMM/submit" '{"kind":"text","title":"still spam","body":"this still contains spamword"}' \
  -H "Authorization: Bearer $MEMBER" -H "Idempotency-Key: p3-spam2-${RUN}"
SPAM2_ID=$(echo "$HTTP_BODY" | jq -r .id)
SPAM2_REMOVED=$(psql_c "SELECT removed FROM posts WHERE id = '$SPAM2_ID'")
expect_eq "after rule deletion, the same keyword no longer auto-removes" "f" "$SPAM2_REMOVED"

################################################################################
echo "=== Phase D: user reports (file, resolve, dismiss) ==="
################################################################################

req POST "/r/$COMM/submit" '{"kind":"text","title":"normal post","body":"nothing wrong here"}' \
  -H "Authorization: Bearer $MEMBER" -H "Idempotency-Key: p3-norm-${RUN}"
NORM_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "member submits a clean post" "200" "$HTTP_STATUS" "id=$NORM_ID"

req POST /api/comment "{\"postId\":\"$NORM_ID\",\"body\":\"a comment to report\"}" -H "Authorization: Bearer $MEMBER"
COMMENT_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "member comments on their own post" "200" "$HTTP_STATUS" "id=$COMMENT_ID"

req POST /api/report "{\"targetType\":\"post\",\"targetId\":\"$NORM_ID\",\"reason\":\"testing dismiss\"}" -H "Authorization: Bearer $OWNER"
DISMISS_REPORT_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "file a report on the post" "200" "$HTTP_STATUS" "id=$DISMISS_REPORT_ID"
req POST "/r/$COMM/mod/reports/$DISMISS_REPORT_ID/dismiss" "" -H "Authorization: Bearer $OWNER"
expect_status "dismiss the report" "200" "$HTTP_STATUS" "-"
POST_STILL_UP=$(psql_c "SELECT removed FROM posts WHERE id = '$NORM_ID'")
expect_eq "dismissing a report leaves the post untouched" "f" "$POST_STILL_UP"

req POST /api/report "{\"targetType\":\"comment\",\"targetId\":\"$COMMENT_ID\",\"reason\":\"testing resolve\"}" -H "Authorization: Bearer $OWNER"
RESOLVE_REPORT_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "file a report on the comment" "200" "$HTTP_STATUS" "id=$RESOLVE_REPORT_ID"
req POST "/r/$COMM/mod/reports/$RESOLVE_REPORT_ID/resolve" "" -H "Authorization: Bearer $OWNER"
expect_status "resolve the report" "200" "$HTTP_STATUS" "-"
REPORT_STATUS=$(psql_c "SELECT status FROM reports WHERE id = '$RESOLVE_REPORT_ID'")
expect_eq "resolved report's status is 'resolved'" "resolved" "$REPORT_STATUS"

################################################################################
echo "=== Phase E: manual removal (post + comment), capped-permission moderator, audit log ==="
################################################################################

req POST "/r/$COMM/mod/remove/comment/$COMMENT_ID" '{"reason":"manual removal test"}' -H "Authorization: Bearer $LIMITED_MOD"
expect_status "capped moderator (PERM_REMOVE_CONTENT) removes a comment" "200" "$HTTP_STATUS" "-"
COMMENT_REMOVED=$(psql_c "SELECT removed FROM comments WHERE id = '$COMMENT_ID'")
expect_eq "comment removed flag set" "t" "$COMMENT_REMOVED"

req POST "/r/$COMM/mod/remove/post/$NORM_ID" '{"reason":"manual removal test"}' -H "Authorization: Bearer $OWNER"
expect_status "owner removes the post" "200" "$HTTP_STATUS" "-"
NORM_REMOVED=$(psql_c "SELECT removed FROM posts WHERE id = '$NORM_ID'")
expect_eq "post removed flag set" "t" "$NORM_REMOVED"

req GET "/r/$COMM/mod/actions" "" -H "Authorization: Bearer $OWNER"
ACTIONS_COUNT=$(echo "$HTTP_BODY" | jq 'length')
if [ "${ACTIONS_COUNT:-0}" -gt 0 ] 2>/dev/null; then
  record PASS "moderation-action audit log recorded this run's actions" "count=$ACTIONS_COUNT"
else
  record FAIL "moderation-action audit log recorded this run's actions" "count=$ACTIONS_COUNT"
fi

################################################################################
echo "=== Phase F: capped-permission moderator is forbidden from ban (permission-bit isolation) ==="
################################################################################

req POST "/r/$COMM/mod/ban" "{\"userId\":\"$MEMBER_ID\",\"reason\":\"should be forbidden\"}" -H "Authorization: Bearer $LIMITED_MOD"
expect_status "moderator without PERM_BAN_USERS cannot ban" "403" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase G: community ban/unban ==="
################################################################################

req POST "/r/$COMM/mod/ban" "{\"userId\":\"$MEMBER_ID\",\"reason\":\"community ban test\"}" -H "Authorization: Bearer $OWNER"
expect_status "owner bans the member from the community" "200" "$HTTP_STATUS" "-"

req POST "/r/$COMM/submit" '{"kind":"text","title":"should fail","body":"banned user"}' \
  -H "Authorization: Bearer $MEMBER" -H "Idempotency-Key: p3-banned-submit-${RUN}"
expect_status "banned member cannot submit to the community" "403" "$HTTP_STATUS" "-"

req POST /api/comment "{\"postId\":\"$SPAM2_ID\",\"body\":\"should also fail\"}" -H "Authorization: Bearer $MEMBER"
expect_status "banned member cannot comment in the community" "403" "$HTTP_STATUS" "-"

req DELETE "/r/$COMM/mod/ban/$MEMBER_ID" "" -H "Authorization: Bearer $OWNER"
expect_status "owner lifts the community ban" "200" "$HTTP_STATUS" "-"
req POST "/r/$COMM/submit" '{"kind":"text","title":"should work now","body":"unbanned user"}' \
  -H "Authorization: Bearer $MEMBER" -H "Idempotency-Key: p3-unbanned-submit-${RUN}"
expect_status "unbanned member can submit again" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase H: mod-mail mute/unmute ==="
################################################################################

req POST "/r/$COMM/mod/mail" '{"body":"hello mods, before mute"}' -H "Authorization: Bearer $MEMBER"
expect_status "member sends mod mail before being muted" "200" "$HTTP_STATUS" "-"

req POST "/r/$COMM/mod/mute" "{\"userId\":\"$MEMBER_ID\",\"reason\":\"modmail mute test\"}" -H "Authorization: Bearer $OWNER"
expect_status "owner mutes the member from mod mail" "200" "$HTTP_STATUS" "-"
req POST "/r/$COMM/mod/mail" '{"body":"hello again, should fail"}' -H "Authorization: Bearer $MEMBER"
expect_status "muted member cannot send mod mail" "403" "$HTTP_STATUS" "-"

req DELETE "/r/$COMM/mod/mute/$MEMBER_ID" "" -H "Authorization: Bearer $OWNER"
expect_status "owner unmutes the member" "200" "$HTTP_STATUS" "-"
req POST "/r/$COMM/mod/mail" '{"body":"hello once more, should work"}' -H "Authorization: Bearer $MEMBER"
expect_status "unmuted member can send mod mail again" "200" "$HTTP_STATUS" "-"

req GET "/r/$COMM/mod/mail" "" -H "Authorization: Bearer $OWNER"
MAIL_COUNT=$(echo "$HTTP_BODY" | jq 'length')
expect_eq "mod mail inbox has both successful sends" "2" "$MAIL_COUNT"

################################################################################
echo "=== Phase I: community-scoped search ==="
################################################################################

req GET "/r/$COMM/search?q=spam" ""
expect_status "unauthenticated community search" "200" "$HTTP_STATUS" "-"
SEARCH_HITS=$(echo "$HTTP_BODY" | jq '.data.children | length')
if [ "${SEARCH_HITS:-0}" -gt 0 ] 2>/dev/null; then
  record PASS "search for 'spam' finds at least one seeded post" "hits=$SEARCH_HITS"
else
  record FAIL "search for 'spam' finds at least one seeded post" "hits=$SEARCH_HITS"
fi

################################################################################
echo "=== Phase J: site-wide (account-level) admin ban/unban ==="
################################################################################

req POST /api/v1/access_token "{\"username\":\"p3${RUN}member\",\"password\":\"$PASSWORD\"}"
expect_status "member can log in before an account-level ban" "200" "$HTTP_STATUS" "-"

req POST "/api/v1/admin/users/$MEMBER_ID/ban" '{"reason":"site-wide ban test"}' -H "Authorization: Bearer $ADMIN"
expect_status "site admin issues an account-level ban" "200" "$HTTP_STATUS" "-"
req POST /api/v1/access_token "{\"username\":\"p3${RUN}member\",\"password\":\"$PASSWORD\"}"
expect_status "account-banned member's login is rejected" "401" "$HTTP_STATUS" "-"

req POST "/api/v1/admin/users/$MEMBER_ID/unban" '{"reason":"site-wide unban test"}' -H "Authorization: Bearer $ADMIN"
expect_status "site admin lifts the account-level ban" "200" "$HTTP_STATUS" "-"
req POST /api/v1/access_token "{\"username\":\"p3${RUN}member\",\"password\":\"$PASSWORD\"}"
expect_status "member can log in again after account-level unban" "200" "$HTTP_STATUS" "-"

req POST "/api/v1/admin/users/$MEMBER_ID/ban" '{"reason":"non-admin attempt"}' -H "Authorization: Bearer $OWNER"
expect_status "a non-site-admin cannot issue an account-level ban" "403" "$HTTP_STATUS" "-"

echo ""
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
