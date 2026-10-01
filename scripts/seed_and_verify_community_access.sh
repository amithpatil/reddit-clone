#!/usr/bin/env bash
# Seeds a small dataset through the live REST API and re-verifies restricted/private communities end to
# end: type persists at creation (and defaults to public when omitted), restricted gates only submitting
# (viewing/subscribing/commenting stay open) via a direct mod-granted approved-submitter list, private
# gates every content-reading endpoint plus submitting via a real request -> mod-approve/deny workflow,
# subscribing to a private community is rejected in favor of requesting access, approval creates real
# membership (subscriber count included) and unlocks both viewing and posting immediately, denial leaves
# the requester locked out but able to request again, only the literal owner can change a community's
# type, flipping an existing community to private doesn't evict current members, and the already-public
# flairs/rules/pinned metadata endpoints stay public even for a private community. Prints PASS/FAIL with
# the real observed value for every check. Data is left in the dev database afterward.
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
  local uname="ca${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

submit() {
  local token="$1" comm="$2" title="$3" key="$4"
  req POST "/r/$comm/submit" "{\"kind\":\"text\",\"title\":\"$title\",\"body\":\"body\"}" \
    -H "Authorization: Bearer $token" -H "Idempotency-Key: $key"
}

################################################################################
echo "=== Phase A: seed owner, outsider, a second moderator ==="
################################################################################

OWNER=$(register owner)
OUTSIDER=$(register outsider)
OUTSIDER_ID=$(psql_c "SELECT id FROM users WHERE username='ca${RUN}outsider'")
MOD2=$(register mod2)
MOD2_ID=$(psql_c "SELECT id FROM users WHERE username='ca${RUN}mod2'")

################################################################################
echo "=== Phase B: type persists at creation; omitting it still defaults to public ==="
################################################################################

PUBCOMM="capub${RUN}"
req POST /r "{\"name\":\"$PUBCOMM\",\"description\":\"no type specified\"}" -H "Authorization: Bearer $OWNER"
expect_status "create a community with no type field at all" "200" "$HTTP_STATUS" "-"
PUB_TYPE_DB=$(psql_c "SELECT type FROM communities WHERE name='$PUBCOMM'")
expect_eq "it defaults to public" "public" "$PUB_TYPE_DB"

RESTCOMM="carest${RUN}"
req POST /r "{\"name\":\"$RESTCOMM\",\"description\":\"restricted seed\",\"type\":\"restricted\"}" -H "Authorization: Bearer $OWNER"
expect_status "create a restricted community" "200" "$HTTP_STATUS" "-"

PRIVCOMM="capriv${RUN}"
req POST /r "{\"name\":\"$PRIVCOMM\",\"description\":\"private seed\",\"type\":\"private\"}" -H "Authorization: Bearer $OWNER"
expect_status "create a private community" "200" "$HTTP_STATUS" "-"
PRIV_TYPE_DB=$(psql_c "SELECT type FROM communities WHERE name='$PRIVCOMM'")
expect_eq "its type persists as private" "private" "$PRIV_TYPE_DB"

################################################################################
echo "=== Phase C: restricted gates only submitting ==="
################################################################################

req POST "/r/$RESTCOMM/subscribe" "" -H "Authorization: Bearer $OUTSIDER"
expect_status "outsider can freely subscribe to a restricted community" "200" "$HTTP_STATUS" "-"
req GET "/r/$RESTCOMM/new" "" -H "Authorization: Bearer $OUTSIDER"
expect_status "outsider can freely view a restricted community's feed" "200" "$HTTP_STATUS" "-"

submit "$OUTSIDER" "$RESTCOMM" "should be rejected" "ca-rest-reject-${RUN}"
expect_status "an unapproved outsider cannot submit to a restricted community" "403" "$HTTP_STATUS" "-"

req POST "/r/$RESTCOMM/mod/approved-submitters" "{\"userId\":\"$OUTSIDER_ID\"}" -H "Authorization: Bearer $OUTSIDER"
expect_status "a non-mod cannot approve a submitter" "403" "$HTTP_STATUS" "-"

req POST "/r/$RESTCOMM/mod/approved-submitters" "{\"userId\":\"$OUTSIDER_ID\"}" -H "Authorization: Bearer $OWNER"
expect_status "mod approves outsider as a submitter" "200" "$HTTP_STATUS" "-"

submit "$OUTSIDER" "$RESTCOMM" "should succeed now" "ca-rest-ok-${RUN}"
RESTRICTED_POST_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "approved outsider can now submit to the restricted community" "200" "$HTTP_STATUS" "id=$RESTRICTED_POST_ID"

req DELETE "/r/$RESTCOMM/mod/approved-submitters/$OUTSIDER_ID" "" -H "Authorization: Bearer $OWNER"
expect_status "mod revokes outsider's approved-submitter status" "200" "$HTTP_STATUS" "-"
submit "$OUTSIDER" "$RESTCOMM" "should be rejected again" "ca-rest-reject2-${RUN}"
expect_status "outsider is blocked from submitting again after revocation" "403" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase D: private gates viewing, subscribing, and commenting ==="
################################################################################

submit "$OWNER" "$PRIVCOMM" "private seed post" "ca-priv-seed-${RUN}"
PRIV_POST_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "owner (an implicit member) submits a post in the private community" "200" "$HTTP_STATUS" "id=$PRIV_POST_ID"

req GET "/r/$PRIVCOMM/new" "" -H "Authorization: Bearer $OUTSIDER"
expect_status "outsider cannot view a private community's feed" "403" "$HTTP_STATUS" "-"
req GET "/r/$PRIVCOMM/hot" "" -H "Authorization: Bearer $OUTSIDER"
expect_status "outsider cannot view /hot either" "403" "$HTTP_STATUS" "-"
req GET "/r/$PRIVCOMM/search?q=seed" "" -H "Authorization: Bearer $OUTSIDER"
expect_status "outsider cannot search a private community" "403" "$HTTP_STATUS" "-"
req GET "/r/$PRIVCOMM/comments/$PRIV_POST_ID" "" -H "Authorization: Bearer $OUTSIDER"
expect_status "outsider cannot view an individual private post" "403" "$HTTP_STATUS" "-"
req GET "/r/$PRIVCOMM/new" ""
expect_status "an unauthenticated request is also rejected" "403" "$HTTP_STATUS" "-"

req POST "/r/$PRIVCOMM/subscribe" "" -H "Authorization: Bearer $OUTSIDER"
expect_status "self-serve subscribe to a private community is rejected" "403" "$HTTP_STATUS" "-"

req POST /api/comment "{\"postId\":\"$PRIV_POST_ID\",\"body\":\"should be rejected\"}" -H "Authorization: Bearer $OUTSIDER"
expect_status "outsider cannot comment on a private community's post" "403" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase E: request -> approve workflow ==="
################################################################################

req POST "/r/$RESTCOMM/join-requests" "" -H "Authorization: Bearer $OUTSIDER"
expect_status "requesting to join a non-private community is rejected" "400" "$HTTP_STATUS" "-"

req POST "/r/$PRIVCOMM/join-requests" "" -H "Authorization: Bearer $OUTSIDER"
expect_status "outsider requests to join the private community" "200" "$HTTP_STATUS" "-"
PENDING_STATUS_DB=$(psql_c "SELECT status FROM community_join_requests WHERE user_id='$OUTSIDER_ID'")
expect_eq "the request is stored as pending" "pending" "$PENDING_STATUS_DB"

req GET "/r/$PRIVCOMM/mod/join-requests" "" -H "Authorization: Bearer $OUTSIDER"
expect_status "a non-mod cannot see the pending requests" "403" "$HTTP_STATUS" "-"

req GET "/r/$PRIVCOMM/mod/join-requests" "" -H "Authorization: Bearer $OWNER"
PENDING_USER_IDS=$(echo "$HTTP_BODY" | jq -r '[.[].userId] | join(",")')
expect_eq "mod sees outsider's request in the pending list" "$OUTSIDER_ID" "$PENDING_USER_IDS"

SUBSCRIBERS_BEFORE=$(psql_c "SELECT subscriber_count FROM communities WHERE name='$PRIVCOMM'")

req POST "/r/$PRIVCOMM/mod/join-requests/$OUTSIDER_ID/approve" "" -H "Authorization: Bearer $OWNER"
expect_status "mod approves outsider's request" "200" "$HTTP_STATUS" "-"

SUBSCRIBERS_AFTER=$(psql_c "SELECT subscriber_count FROM communities WHERE name='$PRIVCOMM'")
EXPECTED_AFTER=$((SUBSCRIBERS_BEFORE + 1))
expect_eq "subscriber count increments on approval" "$EXPECTED_AFTER" "$SUBSCRIBERS_AFTER"

req GET "/r/$PRIVCOMM/new" "" -H "Authorization: Bearer $OUTSIDER"
expect_status "the now-approved outsider can view the private community" "200" "$HTTP_STATUS" "-"

submit "$OUTSIDER" "$PRIVCOMM" "approved member post" "ca-priv-approved-${RUN}"
expect_status "the now-approved outsider can also post (approval alone grants posting)" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase F: a denied request leaves the user locked out but able to re-request ==="
################################################################################

DENY_CANDIDATE=$(register denycandidate)
DENY_CANDIDATE_ID=$(psql_c "SELECT id FROM users WHERE username='ca${RUN}denycandidate'")

req POST "/r/$PRIVCOMM/join-requests" "" -H "Authorization: Bearer $DENY_CANDIDATE"
expect_status "a second user requests to join" "200" "$HTTP_STATUS" "-"

req POST "/r/$PRIVCOMM/mod/join-requests/$DENY_CANDIDATE_ID/deny" "" -H "Authorization: Bearer $OWNER"
expect_status "mod denies the second user's request" "200" "$HTTP_STATUS" "-"

req GET "/r/$PRIVCOMM/new" "" -H "Authorization: Bearer $DENY_CANDIDATE"
expect_status "the denied user still cannot view the community" "403" "$HTTP_STATUS" "-"

req POST "/r/$PRIVCOMM/join-requests" "" -H "Authorization: Bearer $DENY_CANDIDATE"
expect_status "the denied user can request again" "200" "$HTTP_STATUS" "-"
RERQUEST_STATUS_DB=$(psql_c "SELECT status FROM community_join_requests WHERE user_id='$DENY_CANDIDATE_ID'")
expect_eq "the re-request resets cleanly to pending" "pending" "$RERQUEST_STATUS_DB"

################################################################################
echo "=== Phase G: only the owner can change a community's type ==="
################################################################################

# 511 = every currently-named permission bit OR'd together (1+2+4+8+16+32+64+128+256), deliberately NOT
# Integer.MAX_VALUE (OWNER_PERMISSIONS) — granting MAX_VALUE would make mod2 indistinguishable from a real
# owner for the OWNER_PERMISSIONS check below, defeating the point of this test.
req POST "/r/$PRIVCOMM/mod/moderators" "{\"userId\":\"$MOD2_ID\",\"permissions\":511}" -H "Authorization: Bearer $OWNER"
expect_status "owner grants mod2 every named permission bit, but not full ownership" "200" "$HTTP_STATUS" "-"

req PATCH "/r/$PRIVCOMM/mod/type" '{"type":"public"}' -H "Authorization: Bearer $MOD2"
expect_status "a non-owner (even with every other bit) cannot change the community's type" "403" "$HTTP_STATUS" "-"

req PATCH "/r/$PRIVCOMM/mod/type" '{"type":"public"}' -H "Authorization: Bearer $OWNER"
expect_status "the owner changes the community's type to public" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase H: flipping to private doesn't evict current members ==="
################################################################################

req PATCH "/r/$PRIVCOMM/mod/type" '{"type":"private"}' -H "Authorization: Bearer $OWNER"
expect_status "owner flips the community back to private" "200" "$HTTP_STATUS" "-"

req GET "/r/$PRIVCOMM/new" "" -H "Authorization: Bearer $OUTSIDER"
expect_status "the already-approved outsider keeps viewing access with no new request" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase I: public flairs/rules/pinned stay public even for a private community ==="
################################################################################

req GET "/r/$PRIVCOMM/flairs" ""
expect_status "unauthenticated GET flairs still works on a private community" "200" "$HTTP_STATUS" "-"
req GET "/r/$PRIVCOMM/rules" ""
expect_status "unauthenticated GET rules still works on a private community" "200" "$HTTP_STATUS" "-"
req GET "/r/$PRIVCOMM/pinned" ""
expect_status "unauthenticated GET pinned still works on a private community" "200" "$HTTP_STATUS" "-"

echo ""
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
