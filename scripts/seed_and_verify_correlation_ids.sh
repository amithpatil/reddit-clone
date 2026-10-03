#!/usr/bin/env bash
# Verifies the correlation-id infra feature (not a numbered backlog item — see
# reddit_clone_feature_backlog memory): CorrelationIdFilter sets/echoes an X-Correlation-ID per HTTP
# request, GlobalExceptionHandler surfaces it on error bodies, and it survives every async thread hop this
# app has — OutboxWriter persists it onto outbox_events rows, OutboxWorker/NotificationOutboxWorker
# re-apply it per row from their shared @Scheduled thread, MediaService persists it onto Media rows for
# ImageProcessingWorker to re-apply, and ChatStompHandler mints a fresh one per STOMP SEND frame. Checks
# the actual running app's log output (grep), not just HTTP response shapes — a genuinely different kind of
# assertion than this backlog's other scripts, since the whole point of this feature is what ends up in the
# logs, not what a response body says. Prints PASS/FAIL with the real observed value for every check. Data
# (and log output) is left in place afterward.
#
# Prereqs: docker compose stack up, app running on $BASE with its stdout/stderr going to $APP_LOG, ffmpeg
# and node on PATH (for the image-worker and chat checks).

set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
WS_BASE="${WS_BASE:-ws://localhost:8081/ws}"
APP_LOG="${APP_LOG:-/tmp/app.log}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WS_CHECK="$SCRIPT_DIR/chat_ws_check.js"

RUN=$(date +%s | tail -c 6)
PASSWORD="Sup3rSecret!1"
# Worker ticks are every 2s (OutboxWorker/NotificationOutboxWorker/ImageProcessingWorker); this margin
# covers a tick boundary plus actual processing time.
TICK_WAIT=3

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

# segment-contains / segment-not-contains: checks a captured slice of the log (not the whole file) for a
# literal substring, since count-exactness would be brittle (an unrelated job's tick could add noise lines
# around the window) — presence/absence of the exact bracketed id is the actual thing being tested.
check_contains() {
  local desc="$1" segment="$2" needle="$3"
  if printf '%s' "$segment" | grep -F -- "$needle" >/dev/null 2>&1; then
    record PASS "$desc" "found: $needle"
  else
    record FAIL "$desc" "not found: $needle"
  fi
}

check_not_contains() {
  local desc="$1" segment="$2" needle="$3"
  if printf '%s' "$segment" | grep -F -- "$needle" >/dev/null 2>&1; then
    record FAIL "$desc" "unexpectedly found: $needle"
  else
    record PASS "$desc" "correctly absent: $needle"
  fi
}

is_uuid_shaped() {
  [[ "$1" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]
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
  local uname="ci${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  if [ "$HTTP_STATUS" != "200" ]; then
    echo "FATAL: registration of $uname failed (HTTP $HTTP_STATUS) — $HTTP_BODY" >&2
    exit 1
  fi
  echo "$HTTP_BODY" | jq -r .accessToken
}

if [ ! -r "$APP_LOG" ]; then
  echo "FATAL: APP_LOG=$APP_LOG not readable — pass APP_LOG=/path/to/log pointing at the running app's stdout" >&2
  exit 1
fi

################################################################################
echo "=== Phase A: seed owner/voter/third and a community + post ==="
################################################################################

OWNER=$(register owner)
OWNER_NAME="ci${RUN}owner"
VOTER=$(register voter)
THIRD=$(register third)

COMM="cicomm${RUN}"
req POST /r "{\"name\":\"$COMM\",\"description\":\"correlation id seed\",\"type\":\"public\"}" -H "Authorization: Bearer $OWNER"
expect_status "create the seed community" "200" "$HTTP_STATUS" "-"

req POST "/r/$COMM/submit" '{"kind":"text","title":"correlation id test post","body":"b"}' \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: ci-${RUN}-seed-post"
expect_status "submit the seed post" "200" "$HTTP_STATUS" "-"
POST_ID=$(echo "$HTTP_BODY" | jq -r .id)

################################################################################
echo "=== Phase B: HTTP-layer mechanics ==="
################################################################################

EXPLICIT_ID="corr-explicit-${RUN}"
HEADERS=$(curl -s -D- -o /dev/null -H "X-Correlation-ID: $EXPLICIT_ID" "$BASE/actuator/health")
ECHOED=$(echo "$HEADERS" | grep -i '^x-correlation-id:' | tr -d '\r' | awk '{print $2}')
expect_eq "an explicit X-Correlation-ID header is echoed back verbatim" "$EXPLICIT_ID" "$ECHOED"

HEADERS=$(curl -s -D- -o /dev/null "$BASE/actuator/health")
AUTO_ID=$(echo "$HEADERS" | grep -i '^x-correlation-id:' | tr -d '\r' | awk '{print $2}')
if is_uuid_shaped "$AUTO_ID"; then
  record PASS "no header supplied -> server generates a UUID-shaped id" "got $AUTO_ID"
else
  record FAIL "no header supplied -> server generates a UUID-shaped id" "got '$AUTO_ID'"
fi

ERR_ID="corr-errcheck-${RUN}"
req GET "/r/does-not-exist-$RUN/about" "" -H "X-Correlation-ID: $ERR_ID"
expect_status "a 404 on a bogus community still returns" "404" "$HTTP_STATUS" "-"
expect_eq "the error body's correlationId matches the request header" "$ERR_ID" "$(echo "$HTTP_BODY" | jq -r .correlationId)"

################################################################################
echo "=== Phase C: vote -> OutboxWorker propagation ==="
################################################################################

CORR_VOTE="corr-vote-${RUN}"
req POST /api/vote "{\"targetType\":\"post\",\"targetId\":\"$POST_ID\",\"dir\":1}" \
  -H "Authorization: Bearer $VOTER" -H "X-Correlation-ID: $CORR_VOTE"
expect_status "voter upvotes the seed post" "200" "$HTTP_STATUS" "-"

sleep "$TICK_WAIT"
SEGMENT=$(cat "$APP_LOG")
check_contains "vote's correlation id reaches OutboxWorker's happy-path log line" "$SEGMENT" "[$CORR_VOTE]"
VOTE_LINE=$(printf '%s' "$SEGMENT" | grep -F "[$CORR_VOTE]" | grep "applied" | tail -n1)
if [ -n "$VOTE_LINE" ]; then
  record PASS "that log line is specifically OutboxWorker's 'applied' line, not just any line" "$VOTE_LINE"
else
  record FAIL "that log line is specifically OutboxWorker's 'applied' line, not just any line" "no 'applied' line carried $CORR_VOTE"
fi

################################################################################
echo "=== Phase D: comment reply -> NotificationOutboxWorker propagation ==="
################################################################################

CORR_NOTIF="corr-notif-${RUN}"
req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"nice post\"}" \
  -H "Authorization: Bearer $VOTER" -H "X-Correlation-ID: $CORR_NOTIF"
expect_status "voter replies to owner's post (triggers a post_reply notification)" "200" "$HTTP_STATUS" "-"

sleep "$TICK_WAIT"
SEGMENT=$(cat "$APP_LOG")
check_contains "comment's correlation id reaches NotificationOutboxWorker's happy-path log line" "$SEGMENT" "[$CORR_NOTIF]"
NOTIF_LINE=$(printf '%s' "$SEGMENT" | grep -F "[$CORR_NOTIF]" | grep "queued for insertion" | tail -n1)
if [ -n "$NOTIF_LINE" ]; then
  record PASS "that log line is specifically 'queued for insertion', not just any line" "$NOTIF_LINE"
else
  record FAIL "that log line is specifically 'queued for insertion', not just any line" "no such line carried $CORR_NOTIF"
fi

################################################################################
echo "=== Phase E: leak check — per-row MDC clearing across two separate ticks ==="
################################################################################

CORR_LEAK_A="corr-leak-${RUN}-a"
req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"leak check a\"}" \
  -H "Authorization: Bearer $THIRD" -H "X-Correlation-ID: $CORR_LEAK_A"
expect_status "leak-check reply A" "200" "$HTTP_STATUS" "-"
sleep "$TICK_WAIT"
check_contains "leak-check id A appears after its own tick" "$(cat "$APP_LOG")" "[$CORR_LEAK_A]"

MARK=$(wc -l < "$APP_LOG" | tr -d ' ')

CORR_LEAK_B="corr-leak-${RUN}-b"
req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"leak check b\"}" \
  -H "Authorization: Bearer $THIRD" -H "X-Correlation-ID: $CORR_LEAK_B"
expect_status "leak-check reply B" "200" "$HTTP_STATUS" "-"
sleep "$TICK_WAIT"

NEW_SEGMENT=$(tail -n "+$((MARK+1))" "$APP_LOG")
check_contains "leak-check id B appears in the new log output from its own tick" "$NEW_SEGMENT" "[$CORR_LEAK_B]"
check_not_contains "leak-check id A does NOT bleed into B's tick's new log output" "$NEW_SEGMENT" "[$CORR_LEAK_A]"

################################################################################
echo "=== Phase F: image upload -> ImageProcessingWorker propagation ==="
################################################################################

TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT
ffmpeg -y -loglevel error -f lavfi -i "testsrc=size=320x240:duration=1:rate=1" -frames:v 1 "$TMPDIR/img.jpg"
IMG_BYTES=$(stat -f%z "$TMPDIR/img.jpg" 2>/dev/null || stat -c%s "$TMPDIR/img.jpg")

CORR_IMG="corr-img-${RUN}"
req POST /api/media/upload-url "{\"filename\":\"ci.jpg\",\"contentType\":\"image/jpeg\",\"byteSize\":$IMG_BYTES}" \
  -H "Authorization: Bearer $OWNER" -H "X-Correlation-ID: $CORR_IMG"
expect_status "request an image upload URL with a known correlation id" "200" "$HTTP_STATUS" "-"
MID=$(echo "$HTTP_BODY" | jq -r .mediaId)
UPLOAD_URL=$(echo "$HTTP_BODY" | jq -r .uploadUrl)
curl -s -o /dev/null -X PUT "$UPLOAD_URL" -H "Content-Type: image/jpeg" --data-binary "@$TMPDIR/img.jpg"
req POST "/api/media/$MID/complete" "" -H "Authorization: Bearer $OWNER"
expect_status "complete the upload" "200" "$HTTP_STATUS" "-"

sleep "$TICK_WAIT"
SEGMENT=$(cat "$APP_LOG")
check_contains "the upload's correlation id reaches ImageProcessingWorker's happy-path log line" "$SEGMENT" "[$CORR_IMG]"
IMG_LINE=$(printf '%s' "$SEGMENT" | grep -F "[$CORR_IMG]" | grep "image media" | grep "processed" | tail -n1)
if [ -n "$IMG_LINE" ]; then
  record PASS "that log line is specifically the image-processed line, not just any line" "$IMG_LINE"
else
  record FAIL "that log line is specifically the image-processed line, not just any line" "no such line carried $CORR_IMG"
fi

################################################################################
echo "=== Phase G: chat send -> fresh (server-minted) correlation id -> NotificationOutboxWorker ==="
################################################################################

if ! command -v node >/dev/null 2>&1; then
  record INFO "chat check skipped" "node not on PATH"
else
  req POST /api/chat/rooms "{\"participantUsernames\":[\"ci${RUN}voter\"]}" -H "Authorization: Bearer $OWNER"
  expect_status "owner opens a DM room with voter" "200" "$HTTP_STATUS" "-"
  ROOM_ID=$(echo "$HTTP_BODY" | jq -r .id)

  MARK=$(wc -l < "$APP_LOG" | tr -d ' ')
  SEND_RESULT=$(node "$WS_CHECK" send "$WS_BASE" "$OWNER" "$ROOM_ID" "correlation id check" 2>&1)
  expect_eq "owner sends a chat message over STOMP" '{"sent":true}' "$SEND_RESULT"

  sleep "$TICK_WAIT"
  NEW_SEGMENT=$(tail -n "+$((MARK+1))" "$APP_LOG")
  CHAT_LINE=$(printf '%s' "$NEW_SEGMENT" | grep "chat_message" | grep "queued for insertion" | tail -n1)
  if [ -n "$CHAT_LINE" ]; then
    record PASS "a chat_message notification was queued" "$CHAT_LINE"
    # Anchored on the FIRST bracket group specifically (the correlation id) — a greedy .*\[...\].* would
    # instead grab the LAST bracket group on the line (the thread name, e.g. [MessageBroker-9]), since
    # every log line now has two bracket groups.
    CHAT_ID=$(printf '%s' "$CHAT_LINE" | sed -E 's/^[^[]*\[([^]]*)\].*/\1/')
    if is_uuid_shaped "$CHAT_ID"; then
      record PASS "chat's own correlation id is a freshly-minted UUID (not reused from any prior check)" "got $CHAT_ID"
    else
      record FAIL "chat's own correlation id is a freshly-minted UUID (not reused from any prior check)" "got '$CHAT_ID'"
    fi
  else
    record FAIL "a chat_message notification was queued" "no such line found after sending"
  fi
fi

################################################################################
echo
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
