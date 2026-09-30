#!/usr/bin/env bash
# Seeds a small dataset through the live pin/lock REST API and re-verifies both end to end: non-mod
# rejection, a mod pinning a post (visible on GET /pinned and on the post's own JSON), the configured
# max-pinned-posts cap being enforced, unpinning, a mod locking a post (blocks new comments but not votes),
# unlocking, and cross-community pin/lock rejection. Prints PASS/FAIL with the real observed value for
# every check. Data is left in the dev database afterward.
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
  local uname="pl${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

submit() {
  local token="$1" comm="$2" title="$3" key="$4"
  req POST "/r/$comm/submit" "{\"kind\":\"text\",\"title\":\"$title\",\"body\":\"body\"}" \
    -H "Authorization: Bearer $token" -H "Idempotency-Key: $key"
  echo "$HTTP_BODY" | jq -r .id
}

################################################################################
echo "=== Phase A: seed owner (mod), member, second community ==="
################################################################################

OWNER=$(register owner)
MEMBER=$(register member)

COMM="plcomm${RUN}"
req POST /r "{\"name\":\"$COMM\",\"description\":\"pin/lock seed\"}" -H "Authorization: Bearer $OWNER"
expect_status "create seed community" "200" "$HTTP_STATUS" "-"

OTHER_COMM="plother${RUN}"
req POST /r "{\"name\":\"$OTHER_COMM\",\"description\":\"cross-community test\"}" -H "Authorization: Bearer $OWNER"
expect_status "create a second, unrelated community" "200" "$HTTP_STATUS" "-"

POST1=$(submit "$OWNER" "$COMM" "post one" "pl-1-${RUN}")
POST2=$(submit "$OWNER" "$COMM" "post two" "pl-2-${RUN}")
POST3=$(submit "$OWNER" "$COMM" "post three" "pl-3-${RUN}")
OTHER_POST=$(submit "$OWNER" "$OTHER_COMM" "other community post" "pl-other-${RUN}")

################################################################################
echo "=== Phase B: non-mod is forbidden ==="
################################################################################

req POST "/r/$COMM/mod/posts/$POST1/pin" "" -H "Authorization: Bearer $MEMBER"
expect_status "non-mod cannot pin a post" "403" "$HTTP_STATUS" "-"
req POST "/r/$COMM/mod/posts/$POST1/lock" "" -H "Authorization: Bearer $MEMBER"
expect_status "non-mod cannot lock a post" "403" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase C: pinning, GET /pinned, the pinned flag on the post itself ==="
################################################################################

req POST "/r/$COMM/mod/posts/$POST1/pin" "" -H "Authorization: Bearer $OWNER"
expect_status "mod pins post one" "200" "$HTTP_STATUS" "-"

req GET "/r/$COMM/pinned" ""
PINNED_IDS=$(echo "$HTTP_BODY" | jq -r '[.[].id] | join(",")')
expect_eq "GET /pinned shows post one" "$POST1" "$PINNED_IDS"

DB_PINNED=$(psql_c "SELECT pinned FROM posts WHERE id='$POST1'")
expect_eq "post one's pinned column is true" "t" "$DB_PINNED"

################################################################################
echo "=== Phase D: max-pinned-posts cap (default 2) ==="
################################################################################

req POST "/r/$COMM/mod/posts/$POST2/pin" "" -H "Authorization: Bearer $OWNER"
expect_status "mod pins post two (now at the cap)" "200" "$HTTP_STATUS" "-"

req POST "/r/$COMM/mod/posts/$POST3/pin" "" -H "Authorization: Bearer $OWNER"
expect_status "pinning a third post beyond the cap is rejected" "400" "$HTTP_STATUS" "-"

req GET "/r/$COMM/pinned" ""
PINNED_COUNT=$(echo "$HTTP_BODY" | jq 'length')
expect_eq "exactly two posts are pinned, not three" "2" "$PINNED_COUNT"

################################################################################
echo "=== Phase E: unpinning ==="
################################################################################

req DELETE "/r/$COMM/mod/posts/$POST1/pin" "" -H "Authorization: Bearer $OWNER"
expect_status "mod unpins post one" "200" "$HTTP_STATUS" "-"

req GET "/r/$COMM/pinned" ""
PINNED_AFTER_UNPIN=$(echo "$HTTP_BODY" | jq -r '[.[].id] | join(",")')
expect_eq "only post two remains pinned" "$POST2" "$PINNED_AFTER_UNPIN"

# The cap freed up — post three can now be pinned.
req POST "/r/$COMM/mod/posts/$POST3/pin" "" -H "Authorization: Bearer $OWNER"
expect_status "pinning post three succeeds now that the cap has room" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase F: locking blocks new comments but not votes ==="
################################################################################

req POST "/r/$COMM/mod/posts/$POST1/lock" "" -H "Authorization: Bearer $OWNER"
expect_status "mod locks post one" "200" "$HTTP_STATUS" "-"
DB_LOCKED=$(psql_c "SELECT locked FROM posts WHERE id='$POST1'")
expect_eq "post one's locked column is true" "t" "$DB_LOCKED"

req POST /api/comment "{\"postId\":\"$POST1\",\"body\":\"should be rejected\"}" -H "Authorization: Bearer $MEMBER"
expect_status "commenting on a locked post is rejected" "403" "$HTTP_STATUS" "-"

req POST /api/comment "{\"postId\":\"$POST1\",\"body\":\"should also be rejected\"}" -H "Authorization: Bearer $OWNER"
expect_status "even the post's own author cannot comment while locked" "403" "$HTTP_STATUS" "-"

req POST /api/vote "{\"targetType\":\"post\",\"targetId\":\"$POST1\",\"dir\":1}" -H "Authorization: Bearer $MEMBER"
expect_status "voting on a locked post still works" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase G: unlocking restores commenting ==="
################################################################################

req DELETE "/r/$COMM/mod/posts/$POST1/lock" "" -H "Authorization: Bearer $OWNER"
expect_status "mod unlocks post one" "200" "$HTTP_STATUS" "-"

req POST /api/comment "{\"postId\":\"$POST1\",\"body\":\"should succeed now\"}" -H "Authorization: Bearer $MEMBER"
expect_status "commenting works again after unlocking" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase H: cross-community pin/lock is rejected ==="
################################################################################

req POST "/r/$COMM/mod/posts/$OTHER_POST/pin" "" -H "Authorization: Bearer $OWNER"
expect_status "pinning another community's post through this community is rejected" "404" "$HTTP_STATUS" "-"
req POST "/r/$COMM/mod/posts/$OTHER_POST/lock" "" -H "Authorization: Bearer $OWNER"
expect_status "locking another community's post through this community is rejected" "404" "$HTTP_STATUS" "-"

echo ""
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
