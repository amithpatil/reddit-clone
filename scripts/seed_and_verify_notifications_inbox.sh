#!/usr/bin/env bash
# Seeds a small dataset through the live REST + STOMP API and verifies the F9 notifications inbox:
# post_reply/reply/mention rows carry the right actorUsername/postTitle/communityName (the new batched
# display-attach in NotificationService.attachDisplay), a chat_message row resolves actorUsername from
# senderId, single mark-read is owner-scoped (403 for anyone else), the new bulk POST /read-all only
# touches the caller's own rows, and muting post_reply blocks new rows while leaving an already-created
# one alone (un-muting restores delivery). Prints PASS/FAIL with the real observed value for every check.
# Data is left in the dev database afterward.
#
# Prereqs: docker compose stack up, app running on $BASE, node on PATH (for the chat_message leg).

set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
WS_BASE="${WS_BASE:-ws://localhost:8081/ws}"
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5434}"
PGUSER="${PGUSER:-app}"
PGDATABASE="${PGDATABASE:-redditclone}"
export PGPASSWORD="${PGPASSWORD:-devpassword}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WS_CHECK="$SCRIPT_DIR/chat_ws_check.js"

RUN=$(date +%s | tail -c 6)
PASSWORD="Sup3rSecret!1"
OUTBOX_WAIT=3   # NotificationOutboxWorker ticks every 2s

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
  local uname="ni${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

################################################################################
echo "=== Phase A: seed users A (post author), B (commenter), C (mentioned), D (chat partner) ==="
################################################################################

A=$(register a)
B=$(register b)
C=$(register c)
D=$(register d)
A_ID=$(psql_c "SELECT id FROM users WHERE username='ni${RUN}a'")
B_ID=$(psql_c "SELECT id FROM users WHERE username='ni${RUN}b'")
C_ID=$(psql_c "SELECT id FROM users WHERE username='ni${RUN}c'")

COMM="nicomm${RUN}"
req POST /r "{\"name\":\"$COMM\",\"description\":\"notifications inbox seed\"}" -H "Authorization: Bearer $A"
expect_status "A creates seed community" "200" "$HTTP_STATUS" "-"

req POST "/r/$COMM/submit" "{\"kind\":\"text\",\"title\":\"seed post\",\"body\":\"seed body\"}" \
  -H "Authorization: Bearer $A" -H "Idempotency-Key: ni-post-${RUN}"
POST_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "A submits seed post" "200" "$HTTP_STATUS" "id=$POST_ID"

################################################################################
echo "=== Phase B: post_reply — B comments on A's post ==="
################################################################################

req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"nice post\"}" -H "Authorization: Bearer $B"
PARENT_COMMENT_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "B top-level comments on A's post" "200" "$HTTP_STATUS" "id=$PARENT_COMMENT_ID"
sleep "$OUTBOX_WAIT"

req GET /api/notifications "" -H "Authorization: Bearer $A"
expect_status "A fetches their notifications" "200" "$HTTP_STATUS" "-"
POST_REPLY_NOTIF=$(echo "$HTTP_BODY" | jq -r '[.[] | select(.type=="post_reply")] | sort_by(.createdAt) | .[0]')
NOTIF_ID=$(echo "$POST_REPLY_NOTIF" | jq -r .id)
expect_eq "post_reply notification has actorUsername = B" "ni${RUN}b" "$(echo "$POST_REPLY_NOTIF" | jq -r .actorUsername)"
expect_eq "post_reply notification has the right postTitle" "seed post" "$(echo "$POST_REPLY_NOTIF" | jq -r .postTitle)"
expect_eq "post_reply notification has the right communityName" "$COMM" "$(echo "$POST_REPLY_NOTIF" | jq -r .communityName)"
expect_eq "post_reply notification is unread" "null" "$(echo "$POST_REPLY_NOTIF" | jq -r .readAt)"

################################################################################
echo "=== Phase C: reply + mention in one comment — A replies to B, mentioning C ==="
################################################################################

req POST /api/comment "{\"postId\":\"$POST_ID\",\"parentId\":\"$PARENT_COMMENT_ID\",\"body\":\"thanks! u/ni${RUN}c you'd like this too\"}" \
  -H "Authorization: Bearer $A"
expect_status "A replies to B's comment, mentioning C" "200" "$HTTP_STATUS" "-"
sleep "$OUTBOX_WAIT"

req GET /api/notifications "" -H "Authorization: Bearer $B"
REPLY_NOTIF=$(echo "$HTTP_BODY" | jq -r '[.[] | select(.type=="reply")] | .[0]')
expect_eq "reply notification has actorUsername = A" "ni${RUN}a" "$(echo "$REPLY_NOTIF" | jq -r .actorUsername)"
expect_eq "reply notification has the right postTitle" "seed post" "$(echo "$REPLY_NOTIF" | jq -r .postTitle)"

req GET /api/notifications "" -H "Authorization: Bearer $C"
MENTION_NOTIF=$(echo "$HTTP_BODY" | jq -r '[.[] | select(.type=="mention")] | .[0]')
CAN_NOTIF_ID=$(echo "$MENTION_NOTIF" | jq -r .id)
expect_eq "mention notification has actorUsername = A" "ni${RUN}a" "$(echo "$MENTION_NOTIF" | jq -r .actorUsername)"
expect_eq "mention notification has the right communityName" "$COMM" "$(echo "$MENTION_NOTIF" | jq -r .communityName)"

################################################################################
echo "=== Phase D: chat_message — A sends D a chat message ==="
################################################################################

req POST /api/chat/rooms "{\"participantUsernames\":[\"ni${RUN}d\"]}" -H "Authorization: Bearer $A"
ROOM_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "A starts a chat room with D" "200" "$HTTP_STATUS" "-"

SEND_RESULT=$(node "$WS_CHECK" send "$WS_BASE" "$A" "$ROOM_ID" "hi D" 2>&1)
expect_eq "A's live send to D succeeds" '{"sent":true}' "$SEND_RESULT"
sleep "$OUTBOX_WAIT"

req GET /api/notifications "" -H "Authorization: Bearer $D"
CHAT_NOTIF=$(echo "$HTTP_BODY" | jq -r '[.[] | select(.type=="chat_message")] | .[0]')
expect_eq "chat_message notification has actorUsername resolved from senderId" "ni${RUN}a" "$(echo "$CHAT_NOTIF" | jq -r .actorUsername)"
expect_eq "chat_message notification has no postTitle (not applicable to this type)" "null" "$(echo "$CHAT_NOTIF" | jq -r .postTitle)"

################################################################################
echo "=== Phase E: single mark-read is owner-scoped ==="
################################################################################

req POST "/api/notifications/$NOTIF_ID/read" "" -H "Authorization: Bearer $A"
expect_status "A marks their own post_reply notification read" "200" "$HTTP_STATUS" "-"

req GET /api/notifications "" -H "Authorization: Bearer $A"
READ_AT=$(echo "$HTTP_BODY" | jq -r --arg id "$NOTIF_ID" '.[] | select(.id==$id) | .readAt')
if [ "$READ_AT" != "null" ] && [ -n "$READ_AT" ]; then
  record PASS "the marked notification now has a non-null readAt" "readAt=$READ_AT"
else
  record FAIL "the marked notification now has a non-null readAt" "readAt=$READ_AT"
fi

req POST "/api/notifications/$CAN_NOTIF_ID/read" "" -H "Authorization: Bearer $B"
expect_status "B cannot mark C's mention notification read" "403" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase F: bulk mark-all-read only touches the caller's own rows ==="
################################################################################

req GET /api/notifications "" -H "Authorization: Bearer $C"
C_UNREAD_BEFORE=$(echo "$HTTP_BODY" | jq -r '[.[] | select(.readAt==null)] | length')
expect_eq "C still has an unread mention notification before B's read-all" "1" "$C_UNREAD_BEFORE"

req POST /api/notifications/read-all "" -H "Authorization: Bearer $B"
expect_status "B marks all their notifications read" "200" "$HTTP_STATUS" "-"

req GET /api/notifications "" -H "Authorization: Bearer $B"
B_UNREAD_AFTER=$(echo "$HTTP_BODY" | jq -r '[.[] | select(.readAt==null)] | length')
expect_eq "B has zero unread notifications after read-all" "0" "$B_UNREAD_AFTER"

req GET /api/notifications "" -H "Authorization: Bearer $C"
C_UNREAD_AFTER=$(echo "$HTTP_BODY" | jq -r '[.[] | select(.readAt==null)] | length')
expect_eq "C's unread notification is untouched by B's read-all" "1" "$C_UNREAD_AFTER"

################################################################################
echo "=== Phase G: muting post_reply blocks new rows; un-muting restores them ==="
################################################################################

POST_REPLY_COUNT_BEFORE_MUTE=$(psql_c "SELECT count(*) FROM notifications WHERE user_id='$A_ID' AND type='post_reply'")
expect_eq "A has exactly one post_reply notification so far" "1" "$POST_REPLY_COUNT_BEFORE_MUTE"

req PATCH /api/v1/me/prefs '{"notificationPrefs":{"post_reply":false}}' -H "Authorization: Bearer $A"
expect_status "A mutes post_reply notifications" "200" "$HTTP_STATUS" "-"

req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"another top-level comment while muted\"}" -H "Authorization: Bearer $B"
expect_status "B comments again while A has post_reply muted" "200" "$HTTP_STATUS" "-"
sleep "$OUTBOX_WAIT"

POST_REPLY_COUNT_AFTER_MUTE=$(psql_c "SELECT count(*) FROM notifications WHERE user_id='$A_ID' AND type='post_reply'")
expect_eq "no new post_reply notification was created while muted" "1" "$POST_REPLY_COUNT_AFTER_MUTE"

req PATCH /api/v1/me/prefs '{"notificationPrefs":{"post_reply":true}}' -H "Authorization: Bearer $A"
expect_status "A un-mutes post_reply notifications" "200" "$HTTP_STATUS" "-"

req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"a comment after un-muting\"}" -H "Authorization: Bearer $B"
expect_status "B comments a third time after A un-mutes" "200" "$HTTP_STATUS" "-"
sleep "$OUTBOX_WAIT"

POST_REPLY_COUNT_AFTER_UNMUTE=$(psql_c "SELECT count(*) FROM notifications WHERE user_id='$A_ID' AND type='post_reply'")
expect_eq "post_reply notifications fire again after un-muting" "2" "$POST_REPLY_COUNT_AFTER_UNMUTE"

echo ""
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
