#!/usr/bin/env bash
# Verifies backend feature 13 (community description editing, gated by the new PERM_MANAGE_SETTINGS bit)
# end to end against the live app: the creator can edit the description and see it on /about, a non-moderator
# and an anonymous request are rejected, a moderator holding only other bits is rejected until granted
# PERM_MANAGE_SETTINGS (512), an over-length description is rejected, and a moderator holding
# PERM_MANAGE_MODERATORS cannot demote the creator's owner row (checked against the database, not just the
# HTTP status). Prints PASS/FAIL with the real observed value for every check. Data is left in the dev
# database afterward.
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
  local uname="cs${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  if [ "$HTTP_STATUS" != "200" ]; then
    echo "FATAL: registration of $uname failed (HTTP $HTTP_STATUS) — $HTTP_BODY" >&2
    exit 1
  fi
  echo "$HTTP_BODY" | jq -r .accessToken
}

user_id() {
  req GET /api/v1/me "" -H "Authorization: Bearer $1"
  echo "$HTTP_BODY" | jq -r .id
}

################################################################################
echo "=== Phase A: seed a creator, a limited moderator, a moderator-manager, and an outsider ==="
################################################################################

OWNER=$(register owner)
OWNER_ID=$(user_id "$OWNER")
LIMITED=$(register limited)
LIMITED_ID=$(user_id "$LIMITED")
MANAGER=$(register manager)
MANAGER_ID=$(user_id "$MANAGER")
OUTSIDER=$(register outsider)

COMM="cscomm${RUN}"
req POST /r "{\"name\":\"$COMM\",\"description\":\"original description\",\"type\":\"public\"}" -H "Authorization: Bearer $OWNER"
expect_status "create the community" "200" "$HTTP_STATUS" "-"
COMM_ID=$(echo "$HTTP_BODY" | jq -r .id)

req POST "/r/$COMM/mod/moderators" "{\"userId\":\"$LIMITED_ID\",\"permissions\":1}" -H "Authorization: Bearer $OWNER"
expect_status "creator grants the limited moderator only PERM_REMOVE_CONTENT (1)" "200" "$HTTP_STATUS" "-"

req POST "/r/$COMM/mod/moderators" "{\"userId\":\"$MANAGER_ID\",\"permissions\":16}" -H "Authorization: Bearer $OWNER"
expect_status "creator grants the manager only PERM_MANAGE_MODERATORS (16)" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase B: who can edit the description ==="
################################################################################

req PATCH "/r/$COMM/mod/settings" '{"description":"edited by the creator"}' -H "Authorization: Bearer $OWNER"
expect_status "creator edits the description" "200" "$HTTP_STATUS" "-"
expect_eq "the edited description is returned in the response" "edited by the creator" "$(echo "$HTTP_BODY" | jq -r .description)"

req GET "/r/$COMM/about" ""
expect_eq "GET /about reflects the new description" "edited by the creator" "$(echo "$HTTP_BODY" | jq -r .description)"

req PATCH "/r/$COMM/mod/settings" '{"description":"outsider attempt"}' -H "Authorization: Bearer $OUTSIDER"
expect_status "a non-moderator cannot edit the description" "403" "$HTTP_STATUS" "-"

req PATCH "/r/$COMM/mod/settings" '{"description":"anonymous attempt"}'
expect_status "an anonymous request is rejected" "401" "$HTTP_STATUS" "-"

req PATCH "/r/$COMM/mod/settings" '{"description":"limited attempt"}' -H "Authorization: Bearer $LIMITED"
expect_status "a moderator without PERM_MANAGE_SETTINGS is rejected" "403" "$HTTP_STATUS" "-"

req PATCH "/r/$COMM/mod/settings" '{"description":"manager attempt"}' -H "Authorization: Bearer $MANAGER"
expect_status "a moderator with only PERM_MANAGE_MODERATORS is rejected" "403" "$HTTP_STATUS" "-"

req POST "/r/$COMM/mod/moderators" "{\"userId\":\"$LIMITED_ID\",\"permissions\":513}" -H "Authorization: Bearer $OWNER"
expect_status "creator grants the limited moderator PERM_MANAGE_SETTINGS (512) on top of (1)" "200" "$HTTP_STATUS" "-"

req PATCH "/r/$COMM/mod/settings" '{"description":"edited by a granted moderator"}' -H "Authorization: Bearer $LIMITED"
expect_status "a moderator granted PERM_MANAGE_SETTINGS can edit the description" "200" "$HTTP_STATUS" "-"

req GET "/r/$COMM/about" ""
expect_eq "the granted moderator's edit shows on /about" "edited by a granted moderator" "$(echo "$HTTP_BODY" | jq -r .description)"

################################################################################
echo "=== Phase C: validation ==="
################################################################################

OVERLONG=$(printf 'x%.0s' $(seq 1 2001))
req PATCH "/r/$COMM/mod/settings" "{\"description\":\"$OVERLONG\"}" -H "Authorization: Bearer $OWNER"
expect_status "a description over the 2000-char max is rejected" "400" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase D: a moderator-manager cannot demote the creator's owner row ==="
################################################################################

req POST "/r/$COMM/mod/moderators" "{\"userId\":\"$OWNER_ID\",\"permissions\":0}" -H "Authorization: Bearer $MANAGER"
expect_status "a PERM_MANAGE_MODERATORS holder cannot overwrite the creator's row" "400" "$HTTP_STATUS" "-"

OWNER_PERMS=$(psql_c "SELECT permissions FROM community_moderators WHERE community_id='$COMM_ID' AND user_id='$OWNER_ID'")
expect_eq "the creator's owner row is unchanged (still OWNER_PERMISSIONS)" "2147483647" "$OWNER_PERMS"

req PATCH "/r/$COMM/mod/settings" '{"description":"still the creator"}' -H "Authorization: Bearer $OWNER"
expect_status "the creator can still edit settings after the rejected demotion" "200" "$HTTP_STATUS" "-"

req PATCH "/r/$COMM/mod/settings" '{"description":"manager after demotion attempt"}' -H "Authorization: Bearer $MANAGER"
expect_status "the manager's own settings access is unchanged by the rejected attempt (still 403)" "403" "$HTTP_STATUS" "-"

################################################################################
echo
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
