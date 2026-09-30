#!/usr/bin/env bash
# Seeds a small dataset through the live flair REST API and re-verifies both post flair and user flair end
# to end: mod-only flair-list management (create/list/delete, non-mod 403), the public unauthenticated
# listing endpoint with its ?type filter, submission-time post-flair validation (valid attach, cross-
# community rejection, type-mismatch rejection, nonexistent rejection, no-flairId regression), a flaired
# post's flair showing up on a listing page without an extra round trip per post, self-service user-flair
# assignment (valid, type-mismatch rejection, non-member rejection, self-clear), mod-assigned user flair
# (valid, non-mod 403), mod re-flairing/clearing an existing post, and ON DELETE SET NULL clearing both a
# post's and a member's flair_id when the underlying flair definition is deleted rather than failing on an
# FK violation. Prints PASS/FAIL with the real observed value for every check. Data is left in place.
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
  local uname="fl${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

################################################################################
echo "=== Phase A: seed owner (mod), member, second member, community ==="
################################################################################

OWNER=$(register owner)
MEMBER=$(register member)
OTHER_MEMBER=$(register othermember)
MEMBER_ID=$(psql_c "SELECT id FROM users WHERE username='fl${RUN}member'")
OTHER_MEMBER_ID=$(psql_c "SELECT id FROM users WHERE username='fl${RUN}othermember'")

COMM="flcomm${RUN}"
req POST /r "{\"name\":\"$COMM\",\"description\":\"flair seed\"}" -H "Authorization: Bearer $OWNER"
expect_status "create seed community" "200" "$HTTP_STATUS" "body=$HTTP_BODY"

req POST "/r/$COMM/subscribe" "" -H "Authorization: Bearer $MEMBER"
expect_status "member subscribes" "200" "$HTTP_STATUS" "-"
req POST "/r/$COMM/subscribe" "" -H "Authorization: Bearer $OTHER_MEMBER"
expect_status "second member subscribes" "200" "$HTTP_STATUS" "-"

# A second, unrelated community whose flair ids must never be usable against $COMM.
OTHER_COMM="flother${RUN}"
req POST /r "{\"name\":\"$OTHER_COMM\",\"description\":\"cross-community flair test\"}" -H "Authorization: Bearer $OWNER"
expect_status "create a second, unrelated community" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase B: mod-only flair-list management ==="
################################################################################

req POST "/r/$COMM/mod/flairs" '{"text":"not a mod","color":"#FF0000","type":"post"}' -H "Authorization: Bearer $MEMBER"
expect_status "non-mod member cannot create a flair" "403" "$HTTP_STATUS" "-"

req POST "/r/$COMM/mod/flairs" '{"text":"Discussion","color":"#FF4500","type":"post"}' -H "Authorization: Bearer $OWNER"
POST_FLAIR_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "mod creates a post-type flair" "200" "$HTTP_STATUS" "id=$POST_FLAIR_ID"

req POST "/r/$COMM/mod/flairs" '{"text":"Veteran","color":"#0079D3","type":"user"}' -H "Authorization: Bearer $OWNER"
USER_FLAIR_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "mod creates a user-type flair" "200" "$HTTP_STATUS" "id=$USER_FLAIR_ID"

req POST "/r/$COMM/mod/flairs" '{"text":"bad color","color":"blue","type":"post"}' -H "Authorization: Bearer $OWNER"
expect_status "a non-hex color is rejected" "400" "$HTTP_STATUS" "-"

req POST "/r/$OTHER_COMM/mod/flairs" '{"text":"Other","color":"#111111","type":"post"}' -H "Authorization: Bearer $OWNER"
OTHER_COMM_FLAIR_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "mod creates a post-type flair in the second community" "200" "$HTTP_STATUS" "id=$OTHER_COMM_FLAIR_ID"

################################################################################
echo "=== Phase C: public flair listing ==="
################################################################################

req GET "/r/$COMM/flairs" ""
FLAIR_COUNT=$(echo "$HTTP_BODY" | jq 'length')
expect_status "unauthenticated GET flairs" "200" "$HTTP_STATUS" "-"
expect_eq "listing shows both flairs" "2" "$FLAIR_COUNT"

req GET "/r/$COMM/flairs?type=post" ""
POST_TYPE_COUNT=$(echo "$HTTP_BODY" | jq 'length')
expect_eq "?type=post filters to just the post flair" "1" "$POST_TYPE_COUNT"

req GET "/r/$COMM/flairs?type=user" ""
USER_TYPE_COUNT=$(echo "$HTTP_BODY" | jq 'length')
expect_eq "?type=user filters to just the user flair" "1" "$USER_TYPE_COUNT"

req GET "/r/$COMM/flairs?type=bogus" ""
expect_status "an invalid ?type value is rejected" "400" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase D: post-flair at submission time ==="
################################################################################

req POST "/r/$COMM/submit" "{\"kind\":\"text\",\"title\":\"flaired post\",\"body\":\"hello\",\"flairId\":\"$POST_FLAIR_ID\"}" \
  -H "Authorization: Bearer $MEMBER" -H "Idempotency-Key: fl-post-${RUN}"
FLAIRED_POST_ID=$(echo "$HTTP_BODY" | jq -r .id)
RESPONSE_FLAIR_TEXT=$(echo "$HTTP_BODY" | jq -r '.flair.text')
expect_status "submit a post with a valid post-flair id" "200" "$HTTP_STATUS" "id=$FLAIRED_POST_ID"
expect_eq "response includes the attached flair's text" "Discussion" "$RESPONSE_FLAIR_TEXT"

req POST "/r/$COMM/submit" "{\"kind\":\"text\",\"title\":\"no flair here\",\"body\":\"hello\"}" \
  -H "Authorization: Bearer $MEMBER" -H "Idempotency-Key: fl-noflair-${RUN}"
expect_status "submitting with no flairId at all still works (regression)" "200" "$HTTP_STATUS" "-"

req POST "/r/$COMM/submit" "{\"kind\":\"text\",\"title\":\"cross-community flair\",\"body\":\"hello\",\"flairId\":\"$OTHER_COMM_FLAIR_ID\"}" \
  -H "Authorization: Bearer $MEMBER" -H "Idempotency-Key: fl-crosscomm-${RUN}"
expect_status "a cross-community flair id is rejected" "404" "$HTTP_STATUS" "-"

req POST "/r/$COMM/submit" "{\"kind\":\"text\",\"title\":\"wrong type flair\",\"body\":\"hello\",\"flairId\":\"$USER_FLAIR_ID\"}" \
  -H "Authorization: Bearer $MEMBER" -H "Idempotency-Key: fl-wrongtype-${RUN}"
expect_status "a user-type flair id used as post flair is rejected" "400" "$HTTP_STATUS" "-"

req POST "/r/$COMM/submit" "{\"kind\":\"text\",\"title\":\"nonexistent flair\",\"body\":\"hello\",\"flairId\":\"01965f7a-0000-7000-8000-000000000000\"}" \
  -H "Authorization: Bearer $MEMBER" -H "Idempotency-Key: fl-nonexistent-${RUN}"
expect_status "a nonexistent flair id is rejected" "404" "$HTTP_STATUS" "-"

req GET "/r/$COMM/new" "" -H "Authorization: Bearer $MEMBER"
LISTING_FLAIR_TEXT=$(echo "$HTTP_BODY" | jq -r --arg id "$FLAIRED_POST_ID" '.data.children[] | select(.data.id == $id) | .data.flair.text')
expect_eq "the flaired post shows its flair on a listing page" "Discussion" "$LISTING_FLAIR_TEXT"

################################################################################
echo "=== Phase E: self-service user flair ==="
################################################################################

req PATCH "/r/$COMM/me/flair" "{\"flairId\":\"$USER_FLAIR_ID\"}" -H "Authorization: Bearer $MEMBER"
expect_status "member self-assigns their own user flair" "200" "$HTTP_STATUS" "-"
DB_FLAIR=$(psql_c "SELECT flair_id FROM memberships WHERE user_id='$MEMBER_ID' AND community_id=(SELECT id FROM communities WHERE name='$COMM')")
expect_eq "membership row reflects the self-assigned flair" "$USER_FLAIR_ID" "$DB_FLAIR"

req PATCH "/r/$COMM/me/flair" "{\"flairId\":\"$POST_FLAIR_ID\"}" -H "Authorization: Bearer $MEMBER"
expect_status "self-assigning a post-type flair as user flair is rejected" "400" "$HTTP_STATUS" "-"

req PATCH "/r/$OTHER_COMM/me/flair" "{\"flairId\":\"$USER_FLAIR_ID\"}" -H "Authorization: Bearer $MEMBER"
expect_status "self-assigning in a community never joined is rejected" "404" "$HTTP_STATUS" "-"

req PATCH "/r/$COMM/me/flair" '{"flairId":null}' -H "Authorization: Bearer $MEMBER"
expect_status "member self-clears their flair" "200" "$HTTP_STATUS" "-"
DB_FLAIR_CLEARED=$(psql_c "SELECT flair_id IS NULL FROM memberships WHERE user_id='$MEMBER_ID' AND community_id=(SELECT id FROM communities WHERE name='$COMM')")
expect_eq "membership row shows the flair cleared" "t" "$DB_FLAIR_CLEARED"

################################################################################
echo "=== Phase F: mod-assigned user flair ==="
################################################################################

req PATCH "/r/$COMM/mod/users/$OTHER_MEMBER_ID/flair" "{\"flairId\":\"$USER_FLAIR_ID\"}" -H "Authorization: Bearer $MEMBER"
expect_status "non-mod cannot assign another member's flair" "403" "$HTTP_STATUS" "-"

req PATCH "/r/$COMM/mod/users/$OTHER_MEMBER_ID/flair" "{\"flairId\":\"$USER_FLAIR_ID\"}" -H "Authorization: Bearer $OWNER"
expect_status "mod assigns another member's user flair" "200" "$HTTP_STATUS" "-"
DB_OTHER_FLAIR=$(psql_c "SELECT flair_id FROM memberships WHERE user_id='$OTHER_MEMBER_ID' AND community_id=(SELECT id FROM communities WHERE name='$COMM')")
expect_eq "target member's row reflects the mod-assigned flair" "$USER_FLAIR_ID" "$DB_OTHER_FLAIR"

################################################################################
echo "=== Phase G: mod re-flairs / clears an existing post ==="
################################################################################

req POST "/r/$COMM/mod/flairs" '{"text":"Announcement","color":"#46D160","type":"post"}' -H "Authorization: Bearer $OWNER"
SECOND_POST_FLAIR_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "mod creates a second post-type flair" "200" "$HTTP_STATUS" "-"

req PATCH "/r/$COMM/mod/posts/$FLAIRED_POST_ID/flair" "{\"flairId\":\"$SECOND_POST_FLAIR_ID\"}" -H "Authorization: Bearer $MEMBER"
expect_status "non-mod cannot re-flair a post" "403" "$HTTP_STATUS" "-"

req PATCH "/r/$COMM/mod/posts/$FLAIRED_POST_ID/flair" "{\"flairId\":\"$SECOND_POST_FLAIR_ID\"}" -H "Authorization: Bearer $OWNER"
expect_status "mod re-flairs the post" "200" "$HTTP_STATUS" "-"
DB_POST_FLAIR=$(psql_c "SELECT flair_id FROM posts WHERE id='$FLAIRED_POST_ID'")
expect_eq "post row reflects the mod-assigned new flair" "$SECOND_POST_FLAIR_ID" "$DB_POST_FLAIR"

req PATCH "/r/$COMM/mod/posts/$FLAIRED_POST_ID/flair" '{"flairId":null}' -H "Authorization: Bearer $OWNER"
expect_status "mod clears the post's flair" "200" "$HTTP_STATUS" "-"
DB_POST_FLAIR_CLEARED=$(psql_c "SELECT flair_id IS NULL FROM posts WHERE id='$FLAIRED_POST_ID'")
expect_eq "post row shows the flair cleared" "t" "$DB_POST_FLAIR_CLEARED"

################################################################################
echo "=== Phase H: deleting a flair definition clears references (ON DELETE SET NULL) ==="
################################################################################

req PATCH "/r/$COMM/mod/posts/$FLAIRED_POST_ID/flair" "{\"flairId\":\"$SECOND_POST_FLAIR_ID\"}" -H "Authorization: Bearer $OWNER"
expect_status "re-attach the post flair before deleting its definition" "200" "$HTTP_STATUS" "-"

req DELETE "/r/$COMM/mod/flairs/$SECOND_POST_FLAIR_ID" "" -H "Authorization: Bearer $OWNER"
expect_status "mod deletes a flair definition currently referenced by a post" "200" "$HTTP_STATUS" "-"
DB_POST_FLAIR_AFTER_DELETE=$(psql_c "SELECT flair_id IS NULL FROM posts WHERE id='$FLAIRED_POST_ID'")
expect_eq "the post's flair_id is nulled out, not a constraint violation" "t" "$DB_POST_FLAIR_AFTER_DELETE"

req DELETE "/r/$COMM/mod/flairs/$USER_FLAIR_ID" "" -H "Authorization: Bearer $OWNER"
expect_status "mod deletes a flair definition currently referenced by a membership" "200" "$HTTP_STATUS" "-"
DB_MEMBERSHIP_FLAIR_AFTER_DELETE=$(psql_c "SELECT flair_id IS NULL FROM memberships WHERE user_id='$OTHER_MEMBER_ID' AND community_id=(SELECT id FROM communities WHERE name='$COMM')")
expect_eq "the membership's flair_id is nulled out, not a constraint violation" "t" "$DB_MEMBERSHIP_FLAIR_AFTER_DELETE"

req GET "/r/$COMM/flairs" ""
REMAINING_COUNT=$(echo "$HTTP_BODY" | jq 'length')
expect_eq "the deleted flairs no longer appear in the listing" "1" "$REMAINING_COUNT"

echo ""
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
