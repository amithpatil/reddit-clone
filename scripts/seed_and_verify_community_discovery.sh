#!/usr/bin/env bash
# Seeds a small dataset through the live REST API and re-verifies community discovery/browse end to end:
# GET /r/{name}/about returns the right fields with rules as a real JSON array (not a double-encoded
# string, confirming the @JsonIgnore fix), works unauthenticated even for a private community, GET
# /r?sort=new orders by creation time with correct keyset pagination across a page boundary, GET
# /r?sort=popular orders by subscriber count, GET /r/search finds a community by a partial name match
# ranked by similarity, and a private community still appears in both browse and search results (metadata
# stays public). Prints PASS/FAIL with the real observed value for every check. Data is left in the dev
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
  local uname="cd${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

################################################################################
echo "=== Phase A: seed users and communities ==="
################################################################################

OWNER=$(register owner)
SUB1=$(register sub1)
SUB2=$(register sub2)
SUB3=$(register sub3)

OLDCOMM="cdold${RUN}"
req POST /r "{\"name\":\"$OLDCOMM\",\"description\":\"the older one\"}" -H "Authorization: Bearer $OWNER"
expect_status "create the first (older) community" "200" "$HTTP_STATUS" "-"
sleep 1

NEWCOMM="cdnew${RUN}"
req POST /r "{\"name\":\"$NEWCOMM\",\"description\":\"the newer one\"}" -H "Authorization: Bearer $OWNER"
expect_status "create the second (newer) community" "200" "$HTTP_STATUS" "-"

PRIVCOMM="cdpriv${RUN}"
req POST /r "{\"name\":\"$PRIVCOMM\",\"description\":\"private seed\",\"type\":\"private\"}" -H "Authorization: Bearer $OWNER"
expect_status "create a private community" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase B: GET /about ==="
################################################################################

req GET "/r/$NEWCOMM/about" ""
expect_status "unauthenticated GET about works" "200" "$HTTP_STATUS" "-"
ABOUT_NAME=$(echo "$HTTP_BODY" | jq -r .name)
ABOUT_TYPE=$(echo "$HTTP_BODY" | jq -r .type)
ABOUT_DESC=$(echo "$HTTP_BODY" | jq -r .description)
expect_eq "about returns the right name" "$NEWCOMM" "$ABOUT_NAME"
expect_eq "about returns the right type" "public" "$ABOUT_TYPE"
expect_eq "about returns the right description" "the newer one" "$ABOUT_DESC"

RULES_TYPE=$(echo "$HTTP_BODY" | jq -r '.rules | type')
if echo "$HTTP_BODY" | jq -e 'has("rules")' > /dev/null; then
  expect_eq "if rules appears at all, it's a real array, not a double-encoded string" "array" "$RULES_TYPE"
else
  record PASS "rules is absent from the about response entirely (JsonIgnore'd, not double-encoded)" "no rules key"
fi

req GET "/r/$PRIVCOMM/about" ""
expect_status "GET about works unauthenticated even for a private community" "200" "$HTTP_STATUS" "-"
PRIV_ABOUT_TYPE=$(echo "$HTTP_BODY" | jq -r .type)
expect_eq "private community's about correctly shows type=private" "private" "$PRIV_ABOUT_TYPE"

################################################################################
echo "=== Phase C: GET /r?sort=new with pagination ==="
################################################################################

req GET "/r?sort=new" ""
expect_status "GET /r?sort=new works" "200" "$HTTP_STATUS" "-"
FIRST_NEW_NAME=$(echo "$HTTP_BODY" | jq -r '.data.children[0].data.name')
expect_eq "the most recently created community sorts first" "$PRIVCOMM" "$FIRST_NEW_NAME"

AFTER_CURSOR=$(echo "$HTTP_BODY" | jq -r '.data.after')
PAGE1_IDS=$(echo "$HTTP_BODY" | jq -r '[.data.children[].data.id] | join(",")')
req GET "/r?sort=new&after=$AFTER_CURSOR" ""
PAGE2_IDS=$(echo "$HTTP_BODY" | jq -r '[.data.children[].data.id] | join(",")')
if [ -n "$PAGE1_IDS" ] && [ -n "$PAGE2_IDS" ]; then
  OVERLAP=$(comm -12 <(echo "$PAGE1_IDS" | tr ',' '\n' | sort) <(echo "$PAGE2_IDS" | tr ',' '\n' | sort))
  expect_eq "paginating /r?sort=new has no overlap across the page boundary" "" "$OVERLAP"
else
  record FAIL "paginating /r?sort=new has no overlap across the page boundary" "one of the pages was empty"
fi

################################################################################
echo "=== Phase D: GET /r?sort=popular ==="
################################################################################

req POST "/r/$OLDCOMM/subscribe" "" -H "Authorization: Bearer $SUB1"
req POST "/r/$OLDCOMM/subscribe" "" -H "Authorization: Bearer $SUB2"
req POST "/r/$OLDCOMM/subscribe" "" -H "Authorization: Bearer $SUB3"
OLD_SUBS=$(psql_c "SELECT subscriber_count FROM communities WHERE name='$OLDCOMM'")
expect_eq "the older community now has 4 subscribers (owner + 3)" "4" "$OLD_SUBS"

req GET "/r?sort=popular" ""
expect_status "GET /r?sort=popular works" "200" "$HTTP_STATUS" "-"
# This is a long-running shared dev database — many earlier scripts' communities may well have more
# subscribers than this run's 4, so asserting OLDCOMM is the GLOBAL first result would be a flaky test
# assumption, not a real check. What actually matters is that the page is correctly sorted at all.
SUBSCRIBER_COUNTS=$(echo "$HTTP_BODY" | jq -r '[.data.children[].data.subscriberCount] | join(",")')
IS_SORTED=$(python3 -c "
vals = [int(x) for x in '''$SUBSCRIBER_COUNTS'''.split(',') if x != '']
print('true' if all(vals[i] >= vals[i+1] for i in range(len(vals)-1)) else 'false')
")
expect_eq "the popular listing is sorted by subscriber count, descending" "true" "$IS_SORTED"

################################################################################
echo "=== Phase E: GET /r/search ==="
################################################################################

req GET "/r/search?q=$RUN" ""
expect_status "search by a substring of the run-scoped suffix works" "200" "$HTTP_STATUS" "-"
RESULT_COUNT=$(echo "$HTTP_BODY" | jq 'length')
if [ "${RESULT_COUNT:-0}" -ge 3 ] 2>/dev/null; then
  record PASS "search finds all three seeded communities sharing the run suffix" "count=$RESULT_COUNT"
else
  record FAIL "search finds all three seeded communities sharing the run suffix" "count=$RESULT_COUNT"
fi

req GET "/r/search?q=cdold${RUN}" ""
EXACT_MATCH_NAME=$(echo "$HTTP_BODY" | jq -r '.[0].name')
expect_eq "an exact-name query ranks that community first by similarity" "$OLDCOMM" "$EXACT_MATCH_NAME"

req GET "/r/search?q=zzz_no_such_community_xyz" ""
NO_MATCH_COUNT=$(echo "$HTTP_BODY" | jq 'length')
expect_eq "a query matching nothing real returns an empty list" "0" "$NO_MATCH_COUNT"

################################################################################
echo "=== Phase F: a private community's metadata stays public in listings ==="
################################################################################

req GET "/r/search?q=cdpriv${RUN}" ""
PRIV_IN_SEARCH=$(echo "$HTTP_BODY" | jq -r '.[0].name')
expect_eq "the private community is findable by search" "$PRIVCOMM" "$PRIV_IN_SEARCH"

req GET "/r?sort=new" ""
PRIV_IN_BROWSE=$(echo "$HTTP_BODY" | jq -r --arg n "$PRIVCOMM" '[.data.children[].data.name] | any(. == $n)')
expect_eq "the private community appears in the browse listing" "true" "$PRIV_IN_BROWSE"

echo ""
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
