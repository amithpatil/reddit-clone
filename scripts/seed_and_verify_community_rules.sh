#!/usr/bin/env bash
# Seeds a small dataset through the live community-rules REST API and re-verifies end to end: non-mod
# rejection, setting an ordered rules list and reading it back intact, the 15-rule cap, a blank-title rule
# being rejected, whole-list-replace semantics (a shorter replacement actually drops rules rather than
# merging), an empty list being a valid way to clear all rules, and unauthenticated read access. Prints
# PASS/FAIL with the real observed value for every check. Data is left in the dev database afterward.
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
  local uname="cr${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

################################################################################
echo "=== Phase A: seed owner (mod), member, community ==="
################################################################################

OWNER=$(register owner)
MEMBER=$(register member)

COMM="crcomm${RUN}"
req POST /r "{\"name\":\"$COMM\",\"description\":\"rules seed\"}" -H "Authorization: Bearer $OWNER"
expect_status "create seed community" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase B: default rules are empty, non-mod is forbidden ==="
################################################################################

req GET "/r/$COMM/rules" ""
expect_status "unauthenticated GET rules (public)" "200" "$HTTP_STATUS" "-"
expect_eq "a fresh community has no rules by default" "[]" "$HTTP_BODY"

req PUT "/r/$COMM/mod/rules" '{"rules":[{"title":"Be civil","description":"No personal attacks"}]}' -H "Authorization: Bearer $MEMBER"
expect_status "non-mod cannot set rules" "403" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase C: mod sets an ordered rules list, reads it back intact ==="
################################################################################

req PUT "/r/$COMM/mod/rules" '{"rules":[
  {"title":"Be civil","description":"No personal attacks"},
  {"title":"No spam","description":"Self-promotion is limited to 10% of your activity"},
  {"title":"Stay on topic","description":null}
]}' -H "Authorization: Bearer $OWNER"
expect_status "mod sets a 3-rule list" "200" "$HTTP_STATUS" "-"

req GET "/r/$COMM/rules" ""
TITLES=$(echo "$HTTP_BODY" | jq -r '[.[].title] | join(",")')
expect_eq "rules come back in the same order" "Be civil,No spam,Stay on topic" "$TITLES"
SECOND_DESC=$(echo "$HTTP_BODY" | jq -r '.[1].description')
expect_eq "a rule's description round-trips intact" "Self-promotion is limited to 10% of your activity" "$SECOND_DESC"

################################################################################
echo "=== Phase D: validation — max count, blank title ==="
################################################################################

MANY_RULES=$(python3 -c "
import json
print(json.dumps({'rules':[{'title': f'rule {i}', 'description': ''} for i in range(16)]}))
")
req PUT "/r/$COMM/mod/rules" "$MANY_RULES" -H "Authorization: Bearer $OWNER"
expect_status "setting 16 rules (over the 15 cap) is rejected" "400" "$HTTP_STATUS" "-"

req PUT "/r/$COMM/mod/rules" '{"rules":[{"title":"","description":"blank title"}]}' -H "Authorization: Bearer $OWNER"
expect_status "a rule with a blank title is rejected" "400" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase E: whole-list replace, not merge ==="
################################################################################

req PUT "/r/$COMM/mod/rules" '{"rules":[{"title":"Only rule now","description":null}]}' -H "Authorization: Bearer $OWNER"
expect_status "mod replaces the list with a single shorter rule" "200" "$HTTP_STATUS" "-"

req GET "/r/$COMM/rules" ""
RULE_COUNT=$(echo "$HTTP_BODY" | jq 'length')
REMAINING_TITLE=$(echo "$HTTP_BODY" | jq -r '.[0].title')
expect_eq "the old rules are gone, not merged with the new one" "1" "$RULE_COUNT"
expect_eq "only the new rule remains" "Only rule now" "$REMAINING_TITLE"

################################################################################
echo "=== Phase F: an empty list clears all rules ==="
################################################################################

req PUT "/r/$COMM/mod/rules" '{"rules":[]}' -H "Authorization: Bearer $OWNER"
expect_status "mod clears all rules with an empty list" "200" "$HTTP_STATUS" "-"

req GET "/r/$COMM/rules" ""
expect_eq "GET rules now returns an empty list" "[]" "$HTTP_BODY"

echo ""
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
