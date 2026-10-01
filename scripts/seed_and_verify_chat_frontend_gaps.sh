#!/usr/bin/env bash
# Verifies the two backend additions F10 (chat frontend) needed on top of the already-fully-tested chat
# module (scripts/seed_and_verify_chat.sh covers the rest): (1) ChatMessage.senderUsername is now attached
# — both on the REST history page and on the live /queue/chat push — including for a 3-participant group
# room, where client-side "the other participant" guessing would be wrong; (2) the STOMP endpoint's CORS
# origin allowlist fix, re-checked here (not just eyeballed once during planning) so it stays a real
# regression check. Prints PASS/FAIL with the real observed value for every check. Data is left in place.
#
# Prereqs: docker compose stack up, app running on $BASE, node on PATH.

set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
WS_BASE="${WS_BASE:-ws://localhost:8081/ws}"
FRONTEND_ORIGIN="${FRONTEND_ORIGIN:-http://localhost:5174}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WS_CHECK="$SCRIPT_DIR/chat_ws_check.js"

RUN=$(date +%s | tail -c 6)
PASSWORD="Sup3rSecret!1"

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
  local uname="cg${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

################################################################################
echo "=== Phase A: seed a 3-participant group room (alice starts it with bob + carol) ==="
################################################################################

ALICE=$(register alice)
BOB=$(register bob)
CARL=$(register carl)

req POST /api/chat/rooms "{\"participantUsernames\":[\"cg${RUN}bob\",\"cg${RUN}carl\"]}" -H "Authorization: Bearer $ALICE"
ROOM_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "alice creates a 3-participant group room with bob and carl" "200" "$HTTP_STATUS" "id=$ROOM_ID"

################################################################################
echo "=== Phase B: senderUsername on the live /queue/chat push ==="
################################################################################

( node "$WS_CHECK" subscribe-wait "$WS_BASE" "$ALICE" 10 "hello from bob" > /tmp/cg_alice_live.$$ 2>&1 ) &
ALICE_PID=$!
sleep 1
SEND_RESULT=$(node "$WS_CHECK" send "$WS_BASE" "$BOB" "$ROOM_ID" "hello from bob" 2>&1)
expect_eq "bob's live send succeeds" '{"sent":true}' "$SEND_RESULT"
wait $ALICE_PID
LIVE_PUSH=$(cat /tmp/cg_alice_live.$$); rm -f /tmp/cg_alice_live.$$
expect_eq "the live push to alice carries bob's senderUsername" "cg${RUN}bob" "$(echo "$LIVE_PUSH" | jq -r .senderUsername)"
expect_eq "the live push body matches what bob sent" "hello from bob" "$(echo "$LIVE_PUSH" | jq -r .body)"

################################################################################
echo "=== Phase C: senderUsername on the REST history page, including for carl ==="
################################################################################

SEND_RESULT=$(node "$WS_CHECK" send "$WS_BASE" "$CARL" "$ROOM_ID" "hello from carl" 2>&1)
expect_eq "carl's live send succeeds" '{"sent":true}' "$SEND_RESULT"

req GET "/api/chat/rooms/$ROOM_ID/messages" "" -H "Authorization: Bearer $ALICE"
expect_status "alice fetches room history" "200" "$HTTP_STATUS" "-"
BOB_USERNAME_IN_HISTORY=$(echo "$HTTP_BODY" | jq -r --arg body "hello from bob" '.data.children[] | select(.data.body==$body) | .data.senderUsername')
CARL_USERNAME_IN_HISTORY=$(echo "$HTTP_BODY" | jq -r --arg body "hello from carl" '.data.children[] | select(.data.body==$body) | .data.senderUsername')
expect_eq "bob's history row carries his own senderUsername" "cg${RUN}bob" "$BOB_USERNAME_IN_HISTORY"
expect_eq "carl's history row carries his own senderUsername (not bob's, not alice's)" "cg${RUN}carl" "$CARL_USERNAME_IN_HISTORY"

################################################################################
echo "=== Phase D: STOMP endpoint CORS — cross-origin handshake from the real frontend origin succeeds ==="
################################################################################

WS_HOST_PORT=$(echo "$BASE" | sed -E 's#^https?://##')
UPGRADE_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -N \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" -H "Sec-WebSocket-Version: 13" \
  -H "Origin: $FRONTEND_ORIGIN" \
  "http://$WS_HOST_PORT/ws" --max-time 2)
expect_eq "a WebSocket handshake from the frontend's own origin is accepted (was 403 before setAllowedOrigins)" "101" "$UPGRADE_STATUS"

echo ""
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
