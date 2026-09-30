#!/usr/bin/env bash
# Seeds a small dataset through the live chat REST + STOMP-over-WebSocket API and re-verifies real-time
# direct/group messaging end to end: room creation and reopening an existing thread instead of fragmenting
# it into duplicates, a non-participant's 403/404 on history and send, live delivery (two WebSocket
# clients, one sends, the other receives within seconds — the actual real-time-delivery proof this feature
# exists for), STOMP-CONNECT-level JWT auth actually rejecting a missing/garbage token (not just a
# decorative header), room-list summaries (last message + unread count, before/after marking read), keyset
# pagination of message history across more than one page, max-message-length enforcement, a deleted/
# inactive account being unable to send, and the offline-delivery guarantee (a message sent to a recipient
# with zero open WebSocket sessions still persists and still produces a chat_message notification for
# them — proven, not assumed). Bash can't speak STOMP framing, so the real-time checks shell out to
# chat_ws_check.js, a small dependency-free Node script (Node's built-in WebSocket, no npm install) that
# hand-rolls STOMP's simple text framing. Prints PASS/FAIL with the real observed value for every check.
# Data is left in the dev database afterward.
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
  local uname="ch${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

################################################################################
echo "=== Phase A: seed users ==="
################################################################################

ALICE=$(register alice)
BOB=$(register bob)
CAROL=$(register carol)
ALICE_ID=$(psql_c "SELECT id FROM users WHERE username='ch${RUN}alice'")
BOB_ID=$(psql_c "SELECT id FROM users WHERE username='ch${RUN}bob'")

################################################################################
echo "=== Phase B: room creation, reopening an existing thread ==="
################################################################################

req POST /api/chat/rooms "{\"participantUsernames\":[\"ch${RUN}bob\"]}" -H "Authorization: Bearer $ALICE"
ROOM_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "alice starts a chat with bob" "200" "$HTTP_STATUS" "roomId=$ROOM_ID"

req POST /api/chat/rooms "{\"participantUsernames\":[\"ch${RUN}bob\"]}" -H "Authorization: Bearer $ALICE"
ROOM_ID_2=$(echo "$HTTP_BODY" | jq -r .id)
expect_eq "messaging bob again reopens the same room, not a duplicate" "$ROOM_ID" "$ROOM_ID_2"

# Stronger than just re-checking ROOM_ID exists: counts every room whose participant set is exactly
# {alice, bob} — proves the second createRoom call actually found and reused the existing room rather than
# coincidentally returning the same id some other way.
EXACT_ROOM_COUNT=$(psql_c "
  SELECT count(*) FROM (
    SELECT room_id FROM chat_room_participants
    WHERE user_id IN ('$ALICE_ID', '$BOB_ID')
    GROUP BY room_id
    HAVING count(*) = 2 AND count(*) = (SELECT count(*) FROM chat_room_participants p2 WHERE p2.room_id = chat_room_participants.room_id)
  ) x
")
expect_eq "exactly one room exists with alice+bob as its participant set" "1" "$EXACT_ROOM_COUNT"

req POST /api/chat/rooms '{"participantUsernames":["nonexistent_user_xyz"]}' -H "Authorization: Bearer $ALICE"
expect_status "starting a room with a nonexistent user is rejected" "404" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase C: non-participant is forbidden ==="
################################################################################

req GET "/api/chat/rooms/$ROOM_ID/messages" "" -H "Authorization: Bearer $CAROL"
expect_status "a non-participant cannot fetch room history" "404" "$HTTP_STATUS" "-"

CAROL_SEND=$(node "$WS_CHECK" send "$WS_BASE" "$CAROL" "$ROOM_ID" "should be rejected" 2>&1)
if echo "$CAROL_SEND" | grep -q "not a participant\|no such chat room"; then
  record PASS "a non-participant cannot send into the room over STOMP" "error=$CAROL_SEND"
else
  record FAIL "a non-participant cannot send into the room over STOMP" "got=$CAROL_SEND"
fi

################################################################################
echo "=== Phase D: STOMP CONNECT-level JWT auth ==="
################################################################################

MISSING_TOKEN_RESULT=$(node "$WS_CHECK" reject-check "$WS_BASE" "" 2>&1)
if echo "$MISSING_TOKEN_RESULT" | grep -q '"rejected":true'; then
  record PASS "CONNECT with no Authorization header is rejected" "$MISSING_TOKEN_RESULT"
else
  record FAIL "CONNECT with no Authorization header is rejected" "$MISSING_TOKEN_RESULT"
fi

BAD_TOKEN_RESULT=$(node "$WS_CHECK" reject-check "$WS_BASE" "not.a.valid.jwt" 2>&1)
if echo "$BAD_TOKEN_RESULT" | grep -q '"rejected":true'; then
  record PASS "CONNECT with a garbage token is rejected" "$BAD_TOKEN_RESULT"
else
  record FAIL "CONNECT with a garbage token is rejected" "$BAD_TOKEN_RESULT"
fi

################################################################################
echo "=== Phase E: live delivery (the real-time-delivery proof) ==="
################################################################################

( node "$WS_CHECK" subscribe-wait "$WS_BASE" "$BOB" 10 "hello bob, live" > /tmp/chat_verify_bob_live.$$ 2>&1 ) &
BOB_PID=$!
sleep 1
SEND_RESULT=$(node "$WS_CHECK" send "$WS_BASE" "$ALICE" "$ROOM_ID" "hello bob, live" 2>&1)
expect_eq "alice's live send succeeds" '{"sent":true}' "$SEND_RESULT"
wait $BOB_PID
BOB_RESULT=$(cat /tmp/chat_verify_bob_live.$$); rm -f /tmp/chat_verify_bob_live.$$
if echo "$BOB_RESULT" | grep -q "hello bob, live"; then
  record PASS "bob receives alice's message live over his own open WebSocket session" "body seen"
else
  record FAIL "bob receives alice's message live over his own open WebSocket session" "got=$BOB_RESULT"
fi

################################################################################
echo "=== Phase F: offline delivery guarantee ==="
################################################################################

# Bob has no open WebSocket session at this point (Phase E's closed after receiving) — sending to him now
# proves persistence + notification fan-out don't depend on a live connection being open.
OFFLINE_BODY="offline-msg-${RUN}"
node "$WS_CHECK" send "$WS_BASE" "$ALICE" "$ROOM_ID" "$OFFLINE_BODY" > /dev/null 2>&1
sleep 1
PERSISTED=$(psql_c "SELECT count(*) FROM chat_messages WHERE room_id='$ROOM_ID' AND body='$OFFLINE_BODY'")
expect_eq "a message sent while the recipient is offline is still persisted" "1" "$PERSISTED"
sleep 3   # NotificationOutboxWorker ticks every 2s
NOTIF_COUNT=$(psql_c "SELECT count(*) FROM notifications WHERE type='chat_message' AND source->>'roomId'='$ROOM_ID'")
if [ "${NOTIF_COUNT:-0}" -gt 0 ] 2>/dev/null; then
  record PASS "an offline recipient still gets a chat_message notification" "count=$NOTIF_COUNT"
else
  record FAIL "an offline recipient still gets a chat_message notification" "count=$NOTIF_COUNT"
fi

################################################################################
echo "=== Phase G: room list summaries, unread count, mark-read ==="
################################################################################

req GET /api/chat/rooms "" -H "Authorization: Bearer $BOB"
BOB_ROOM=$(echo "$HTTP_BODY" | jq --arg id "$ROOM_ID" '.[] | select(.roomId == $id)')
LAST_BODY=$(echo "$BOB_ROOM" | jq -r .lastMessageBody)
UNREAD_BEFORE=$(echo "$BOB_ROOM" | jq -r .unreadCount)
expect_eq "bob's room list shows the last message body" "$OFFLINE_BODY" "$LAST_BODY"
if [ "${UNREAD_BEFORE:-0}" -gt 0 ] 2>/dev/null; then
  record PASS "bob's unread count is nonzero before marking read" "unread=$UNREAD_BEFORE"
else
  record FAIL "bob's unread count is nonzero before marking read" "unread=$UNREAD_BEFORE"
fi

req POST "/api/chat/rooms/$ROOM_ID/read" "" -H "Authorization: Bearer $BOB"
expect_status "bob marks the room read" "200" "$HTTP_STATUS" "-"

req GET /api/chat/rooms "" -H "Authorization: Bearer $BOB"
UNREAD_AFTER=$(echo "$HTTP_BODY" | jq --arg id "$ROOM_ID" '.[] | select(.roomId == $id) | .unreadCount')
expect_eq "bob's unread count is zero after marking read" "0" "$UNREAD_AFTER"

################################################################################
echo "=== Phase H: message history pagination ==="
################################################################################

node "$WS_CHECK" send-many "$WS_BASE" "$ALICE" "$ROOM_ID" 55 "page-${RUN}" > /dev/null 2>&1
sleep 1

req GET "/api/chat/rooms/$ROOM_ID/messages" "" -H "Authorization: Bearer $ALICE"
PAGE1_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
AFTER_CURSOR=$(echo "$HTTP_BODY" | jq -r '.data.after')
expect_eq "first history page is full (page size 50)" "50" "$PAGE1_COUNT"

req GET "/api/chat/rooms/$ROOM_ID/messages?after=$AFTER_CURSOR" "" -H "Authorization: Bearer $ALICE"
PAGE2_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
if [ "${PAGE2_COUNT:-0}" -gt 0 ] 2>/dev/null; then
  record PASS "second history page returns the remaining messages" "count=$PAGE2_COUNT"
else
  record FAIL "second history page returns the remaining messages" "count=$PAGE2_COUNT"
fi

################################################################################
echo "=== Phase I: max message length ==="
################################################################################

TOO_LONG=$(python3 -c "print('a' * 4001)")
LONG_RESULT=$(node "$WS_CHECK" send "$WS_BASE" "$ALICE" "$ROOM_ID" "$TOO_LONG" 2>&1)
if echo "$LONG_RESULT" | grep -q "exceeds max length"; then
  record PASS "a message over the configured max length is rejected" "-"
else
  record FAIL "a message over the configured max length is rejected" "got=$LONG_RESULT"
fi

################################################################################
echo "=== Phase J: a deleted/inactive account cannot send ==="
################################################################################

DAVE=$(register dave)
req POST /api/chat/rooms "{\"participantUsernames\":[\"ch${RUN}dave\"]}" -H "Authorization: Bearer $ALICE"
DAVE_ROOM_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "alice starts a chat with dave" "200" "$HTTP_STATUS" "-"

req DELETE /api/v1/me "{\"password\":\"$PASSWORD\"}" -H "Authorization: Bearer $DAVE"
expect_status "dave deletes his account" "200" "$HTTP_STATUS" "-"

DAVE_SEND=$(node "$WS_CHECK" send "$WS_BASE" "$DAVE" "$DAVE_ROOM_ID" "should be rejected" 2>&1)
if echo "$DAVE_SEND" | grep -q "not active"; then
  record PASS "a deleted account's still-valid token cannot send a chat message" "error seen"
else
  record FAIL "a deleted account's still-valid token cannot send a chat message" "got=$DAVE_SEND"
fi

echo ""
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
