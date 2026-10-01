#!/usr/bin/env bash
# Seeds a small dataset through the live REST + STOMP API and re-verifies per-notification-type mute
# preferences end to end: a user with no customized prefs gets every type (regression baseline), muting
# "mention" blocks only mention notifications while reply/post_reply still fire, muting "chat_message"
# blocks the notification row but NOT the live WebSocket push itself (proving the mute only affects the
# inbox, never message delivery), un-muting restores it, and a later PATCH touching only nsfwBlur doesn't
# wipe the earlier mute (merge semantics, mirroring the existing privacyPrefs merge behavior). Prints
# PASS/FAIL with the real observed value for every check. Data is left in the dev database afterward.
#
# Prereqs: docker compose stack up, app running on $BASE, node on PATH.

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
  local uname="np${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

################################################################################
echo "=== Phase A: seed owner, commenter, community, seed post ==="
################################################################################

OWNER=$(register owner)
COMMENTER=$(register commenter)
OWNER_ID=$(psql_c "SELECT id FROM users WHERE username='np${RUN}owner'")

COMM="npcomm${RUN}"
req POST /r "{\"name\":\"$COMM\",\"description\":\"notif prefs seed\"}" -H "Authorization: Bearer $OWNER"
expect_status "create seed community" "200" "$HTTP_STATUS" "-"

req POST "/r/$COMM/submit" "{\"kind\":\"text\",\"title\":\"seed post\",\"body\":\"seed body\"}" \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: np-post-${RUN}"
POST_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "submit seed post" "200" "$HTTP_STATUS" "id=$POST_ID"

################################################################################
echo "=== Phase B: baseline — no customized prefs, every type fires ==="
################################################################################

req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"nice post u/np${RUN}owner!\"}" -H "Authorization: Bearer $COMMENTER"
expect_status "commenter replies to owner's post, mentioning owner" "200" "$HTTP_STATUS" "-"
sleep "$OUTBOX_WAIT"

req GET /api/notifications "" -H "Authorization: Bearer $OWNER"
BASELINE_TYPES=$(echo "$HTTP_BODY" | jq -r '[.[].type] | sort | join(",")')
expect_eq "owner has both post_reply and mention notifications by default" "mention,post_reply" "$BASELINE_TYPES"

################################################################################
echo "=== Phase C: muting 'mention' blocks only that type ==="
################################################################################

req PATCH /api/v1/me/prefs '{"notificationPrefs":{"mention":false}}' -H "Authorization: Bearer $OWNER"
expect_status "owner mutes mention notifications" "200" "$HTTP_STATUS" "-"
STORED_PREF=$(echo "$HTTP_BODY" | jq -r '.notificationPrefs.mention')
expect_eq "PATCH response reflects the stored mute" "false" "$STORED_PREF"

req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"another mention u/np${RUN}owner and a reply\"}" -H "Authorization: Bearer $COMMENTER"
expect_status "commenter replies again, mentioning owner again" "200" "$HTTP_STATUS" "-"
sleep "$OUTBOX_WAIT"

MENTION_COUNT_AFTER_MUTE=$(psql_c "SELECT count(*) FROM notifications WHERE user_id='$OWNER_ID' AND type='mention'")
expect_eq "no new mention notification was created while muted" "1" "$MENTION_COUNT_AFTER_MUTE"

POST_REPLY_COUNT_AFTER_MUTE=$(psql_c "SELECT count(*) FROM notifications WHERE user_id='$OWNER_ID' AND type='post_reply'")
expect_eq "post_reply notifications still fire (mute is per-type, not blanket)" "2" "$POST_REPLY_COUNT_AFTER_MUTE"

################################################################################
echo "=== Phase D: muting 'chat_message' blocks the inbox row, not live delivery ==="
################################################################################

req PATCH /api/v1/me/prefs '{"notificationPrefs":{"chat_message":false}}' -H "Authorization: Bearer $OWNER"
expect_status "owner also mutes chat_message notifications" "200" "$HTTP_STATUS" "-"

req POST /api/chat/rooms "{\"participantUsernames\":[\"np${RUN}owner\"]}" -H "Authorization: Bearer $COMMENTER"
ROOM_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "commenter starts a chat with owner" "200" "$HTTP_STATUS" "-"

( node "$WS_CHECK" subscribe-wait "$WS_BASE" "$OWNER" 10 "hello while muted" > /tmp/np_owner_live.$$ 2>&1 ) &
OWNER_PID=$!
sleep 1
SEND_RESULT=$(node "$WS_CHECK" send "$WS_BASE" "$COMMENTER" "$ROOM_ID" "hello while muted" 2>&1)
expect_eq "commenter's live send succeeds" '{"sent":true}' "$SEND_RESULT"
wait $OWNER_PID
LIVE_RESULT=$(cat /tmp/np_owner_live.$$); rm -f /tmp/np_owner_live.$$
if echo "$LIVE_RESULT" | grep -q "hello while muted"; then
  record PASS "owner still receives the chat message live despite muting chat_message notifications" "body seen"
else
  record FAIL "owner still receives the chat message live despite muting chat_message notifications" "got=$LIVE_RESULT"
fi

sleep "$OUTBOX_WAIT"
CHAT_NOTIF_COUNT=$(psql_c "SELECT count(*) FROM notifications WHERE user_id='$OWNER_ID' AND type='chat_message'")
expect_eq "no chat_message notification row was created while muted" "0" "$CHAT_NOTIF_COUNT"

################################################################################
echo "=== Phase E: un-muting restores the notification ==="
################################################################################

req PATCH /api/v1/me/prefs '{"notificationPrefs":{"mention":true}}' -H "Authorization: Bearer $OWNER"
expect_status "owner un-mutes mention notifications" "200" "$HTTP_STATUS" "-"

req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"third mention u/np${RUN}owner\"}" -H "Authorization: Bearer $COMMENTER"
expect_status "commenter mentions owner a third time" "200" "$HTTP_STATUS" "-"
sleep "$OUTBOX_WAIT"

MENTION_COUNT_AFTER_UNMUTE=$(psql_c "SELECT count(*) FROM notifications WHERE user_id='$OWNER_ID' AND type='mention'")
expect_eq "mention notifications fire again after un-muting" "2" "$MENTION_COUNT_AFTER_UNMUTE"

################################################################################
echo "=== Phase F: an unrelated PATCH doesn't wipe the earlier chat_message mute ==="
################################################################################

req PATCH /api/v1/me/prefs '{"nsfwBlur":false}' -H "Authorization: Bearer $OWNER"
expect_status "owner patches only nsfwBlur" "200" "$HTTP_STATUS" "-"
SURVIVING_MUTE=$(echo "$HTTP_BODY" | jq -r '.notificationPrefs.chat_message')
expect_eq "the earlier chat_message mute survives an unrelated PATCH (merge, not replace)" "false" "$SURVIVING_MUTE"

echo ""
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
