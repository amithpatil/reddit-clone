#!/usr/bin/env bash
# Seeds a small dataset through the live REST API and re-verifies backend feature 7 (sitewide search) end
# to end: GET /r/all/search now returns posts ranked across every community instead of 404ing on "no such
# community named all" (the exact gap feature 7 closes), a brand-new private-community exclusion filter on
# that sitewide query (non-members/anonymous never see a private community's posts; a member or moderator
# does), the pre-existing per-community /r/{name}/search endpoint still works unchanged, and the new
# GET /user/search endpoint (fuzzy/partial username match, excluding banned/deleted accounts). Prints
# PASS/FAIL with the real observed value for every check. Data is left in the dev database afterward.
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
  local uname="ss${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

submit() {
  local token="$1" comm="$2" title="$3" key="$4"
  req POST "/r/$comm/submit" "{\"kind\":\"text\",\"title\":\"$title\",\"body\":\"body\"}" \
    -H "Authorization: Bearer $token" -H "Idempotency-Key: $key"
}

KW="zzzsitewidekw${RUN}"

################################################################################
echo "=== Phase A: seed owner/member/outsider and a public + private community ==="
################################################################################

OWNER=$(register owner)
MEMBER=$(register member)
OUTSIDER=$(register outsider)
MEMBER_ID=$(psql_c "SELECT id FROM users WHERE username='ss${RUN}member'")

PUBCOMM="sspub${RUN}"
req POST /r "{\"name\":\"$PUBCOMM\",\"description\":\"public seed\",\"type\":\"public\"}" -H "Authorization: Bearer $OWNER"
expect_status "create the public community" "200" "$HTTP_STATUS" "-"

PRIVCOMM="sspriv${RUN}"
req POST /r "{\"name\":\"$PRIVCOMM\",\"description\":\"private seed\",\"type\":\"private\"}" -H "Authorization: Bearer $OWNER"
expect_status "create the private community" "200" "$HTTP_STATUS" "-"

req POST "/r/$PRIVCOMM/join-requests" "" -H "Authorization: Bearer $MEMBER"
expect_status "member requests to join the private community" "200" "$HTTP_STATUS" "-"
req POST "/r/$PRIVCOMM/mod/join-requests/$MEMBER_ID/approve" "" -H "Authorization: Bearer $OWNER"
expect_status "owner approves the member's join request" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase B: a post with a shared keyword in each community ==="
################################################################################

submit "$OWNER" "$PUBCOMM" "the $KW public post" "ss-pub-${RUN}"
expect_status "owner posts the public keyword post" "200" "$HTTP_STATUS" "-"
PUB_POST_ID=$(echo "$HTTP_BODY" | jq -r .id)

submit "$OWNER" "$PRIVCOMM" "the $KW private post" "ss-priv-${RUN}"
expect_status "owner posts the private keyword post" "200" "$HTTP_STATUS" "-"
PRIV_POST_ID=$(echo "$HTTP_BODY" | jq -r .id)

################################################################################
echo "=== Phase C: GET /r/all/search — the sitewide post-search gap this feature closes ==="
################################################################################

req GET "/r/all/search?q=$KW" ""
expect_status "anonymous GET /r/all/search no longer 404s on the 'all' pseudo-community" "200" "$HTTP_STATUS" "-"
ANON_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
expect_eq "anonymous sees only the public post, not the private one" "1" "$ANON_COUNT"
ANON_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id')
expect_eq "the one post anonymous sees is the public one" "$PUB_POST_ID" "$ANON_IDS"

req GET "/r/all/search?q=$KW" "" -H "Authorization: Bearer $OUTSIDER"
OUTSIDER_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
expect_eq "a non-member also sees only the public post" "1" "$OUTSIDER_COUNT"

req GET "/r/all/search?q=$KW" "" -H "Authorization: Bearer $MEMBER"
MEMBER_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
expect_eq "an approved private-community member sees both posts" "2" "$MEMBER_COUNT"
MEMBER_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id' | sort)
EXPECTED_IDS=$(printf '%s\n%s' "$PUB_POST_ID" "$PRIV_POST_ID" | sort)
expect_eq "the member's two results are exactly the public + private posts" "$EXPECTED_IDS" "$MEMBER_IDS"

req GET "/r/all/search?q=$KW" "" -H "Authorization: Bearer $OWNER"
OWNER_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
expect_eq "the private community's own moderator sees both posts too" "2" "$OWNER_COUNT"

req GET "/r/all/search?q=zzznonexistentqueryxyz${RUN}" ""
expect_status "a nonsense query on /r/all/search still returns 200, not an error" "200" "$HTTP_STATUS" "-"
NONSENSE_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
expect_eq "a nonsense query returns an empty list" "0" "$NONSENSE_COUNT"

################################################################################
echo "=== Phase D: regression — the pre-existing per-community /r/{name}/search is unchanged ==="
################################################################################

req GET "/r/$PUBCOMM/search?q=$KW" ""
expect_status "per-community search on the public community still works" "200" "$HTTP_STATUS" "-"
SCOPED_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
expect_eq "per-community search returns just that community's matching post" "1" "$SCOPED_COUNT"

req GET "/r/$PRIVCOMM/search?q=$KW" ""
expect_status "an anonymous per-community search on the private community is still rejected" "403" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase E: GET /user/search — new username/people search ==="
################################################################################

UPREFIX="zzsu${RUN}"
req POST /api/v1/register "{\"username\":\"${UPREFIX}alpha\",\"email\":\"${UPREFIX}alpha@example.com\",\"password\":\"$PASSWORD\"}"
req POST /api/v1/register "{\"username\":\"${UPREFIX}beta\",\"email\":\"${UPREFIX}beta@example.com\",\"password\":\"$PASSWORD\"}"
req POST /api/v1/register "{\"username\":\"${UPREFIX}gamma\",\"email\":\"${UPREFIX}gamma@example.com\",\"password\":\"$PASSWORD\"}"
GAMMA_ID=$(psql_c "SELECT id FROM users WHERE username='${UPREFIX}gamma'")

req GET "/user/search?q=$UPREFIX" ""
expect_status "GET /user/search is public, no auth required" "200" "$HTTP_STATUS" "-"
BEFORE_BAN_COUNT=$(echo "$HTTP_BODY" | jq 'length')
expect_eq "all three similarly-named users are found before any ban" "3" "$BEFORE_BAN_COUNT"

ADMIN=$(register admin)
psql_c "UPDATE users SET is_site_admin = true WHERE username = 'ss${RUN}admin'" > /dev/null
req POST "/api/v1/admin/users/$GAMMA_ID/ban" '{"reason":"sitewide search exclusion test"}' -H "Authorization: Bearer $ADMIN"
expect_status "site admin bans the gamma test account" "200" "$HTTP_STATUS" "-"

req GET "/user/search?q=$UPREFIX" ""
AFTER_BAN_COUNT=$(echo "$HTTP_BODY" | jq 'length')
expect_eq "the banned account no longer appears in username search" "2" "$AFTER_BAN_COUNT"
AFTER_BAN_NAMES=$(echo "$HTTP_BODY" | jq -r '.[].username' | tr 'A-Z' 'a-z' | sort)
EXPECTED_NAMES=$(printf '%s\n%s' "${UPREFIX}alpha" "${UPREFIX}beta" | tr 'A-Z' 'a-z' | sort)
expect_eq "the remaining two results are exactly alpha and beta" "$EXPECTED_NAMES" "$AFTER_BAN_NAMES"

req GET "/user/search?q=zzznonexistentusernamexyz${RUN}" ""
expect_status "a nonsense username query still returns 200" "200" "$HTTP_STATUS" "-"
NONSENSE_USER_COUNT=$(echo "$HTTP_BODY" | jq 'length')
expect_eq "a nonsense username query returns an empty list" "0" "$NONSENSE_USER_COUNT"

################################################################################
echo "=== Phase F: regression — GET /r/search (community search) is unaffected ==="
################################################################################

req GET "/r/search?q=$PUBCOMM" ""
expect_status "community-name search still works" "200" "$HTTP_STATUS" "-"
COMM_SEARCH_COUNT=$(echo "$HTTP_BODY" | jq 'length')
expect_eq "it finds the seeded public community by name" "1" "$COMM_SEARCH_COUNT"

################################################################################
echo
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
