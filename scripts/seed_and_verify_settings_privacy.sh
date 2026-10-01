#!/usr/bin/env bash
# Verifies F11's one real new backend behavior: privacyPrefs.restrictChatToKnown. A user can opt into
# "only people I've already talked to can message me" — a stranger's attempt to start a brand-new chat room
# with them then 403s, but a user who already shares an existing room with them is unaffected, and unsetting
# the flag restores the old (unrestricted) behavior. nsfwBlur/theme are bare privacyPrefs round-trips already
# covered by seed_and_verify_phase4.sh, so this script is scoped to just the new enforcement logic. Prints
# PASS/FAIL with the real observed value for every check. Data is left in place.
#
# Prereqs: docker compose stack up, app running on $BASE.

set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
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
  local uname="sp${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

################################################################################
echo "=== Phase A: seed A (will restrict), B (stranger), C (will already know A) ==="
################################################################################

A=$(register a)
B=$(register b)
C=$(register c)

################################################################################
echo "=== Phase B: baseline — before restricting, a stranger can message A ==="
################################################################################

req POST /api/chat/rooms "{\"participantUsernames\":[\"sp${RUN}a\"]}" -H "Authorization: Bearer $B"
expect_status "baseline: B (stranger) can start a room with A before any restriction" "200" "$HTTP_STATUS" "-"

# C talks to A first, establishing a real "already known" relationship before A restricts.
req POST /api/chat/rooms "{\"participantUsernames\":[\"sp${RUN}a\"]}" -H "Authorization: Bearer $C"
expect_status "C starts a room with A (establishes a prior relationship)" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase C: A restricts to known contacts only ==="
################################################################################

req PATCH /api/v1/me/prefs '{"privacyPrefs":{"restrictChatToKnown":true}}' -H "Authorization: Bearer $A"
expect_status "A sets restrictChatToKnown=true" "200" "$HTTP_STATUS" "-"
STORED=$(echo "$HTTP_BODY" | jq -r '.privacyPrefs.restrictChatToKnown')
expect_eq "PATCH response reflects the stored restriction" "true" "$STORED"

################################################################################
echo "=== Phase D: a NEW stranger is rejected; the already-known contact still works ==="
################################################################################

D=$(register d)
req POST /api/chat/rooms "{\"participantUsernames\":[\"sp${RUN}a\"]}" -H "Authorization: Bearer $D"
expect_status "a brand-new stranger D is rejected from starting a room with A" "403" "$HTTP_STATUS" "-"

req POST /api/chat/rooms "{\"participantUsernames\":[\"sp${RUN}a\"]}" -H "Authorization: Bearer $C"
expect_status "C (already shares a room with A) can still message A" "200" "$HTTP_STATUS" "-"

req POST /api/chat/rooms "{\"participantUsernames\":[\"sp${RUN}a\"]}" -H "Authorization: Bearer $B"
expect_status "B (already shared a room with A before the restriction) can still message A" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase E: unsetting the restriction restores the old behavior ==="
################################################################################

req PATCH /api/v1/me/prefs '{"privacyPrefs":{"restrictChatToKnown":false}}' -H "Authorization: Bearer $A"
expect_status "A unsets restrictChatToKnown" "200" "$HTTP_STATUS" "-"

req POST /api/chat/rooms "{\"participantUsernames\":[\"sp${RUN}a\"]}" -H "Authorization: Bearer $D"
expect_status "D can now start a room with A after the restriction is lifted" "200" "$HTTP_STATUS" "-"

echo ""
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
