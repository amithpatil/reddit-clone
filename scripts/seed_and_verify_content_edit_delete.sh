#!/usr/bin/env bash
# Verifies backend feature 12 (author self-edit and self-delete of posts and comments) end to end against the
# live app: editing a text post's or comment's body (author-only, sanitized the same way create is, rejected
# for link posts and blank bodies), deleting a comment that has replies (the row and its reply subtree
# survive as a "[deleted]" tombstone), deleting a post (it leaves every listing but its detail page still
# loads as a tombstone with its comments intact), idempotent re-delete, non-author rejection, and that
# moderator-removed content stays immutable for its author. Also checks that a deleted post drops out of the
# anonymous /hot listing once its cache TTL elapses (accepted staleness, same as a moderator removal). Prints
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
HOT_WAIT_SECONDS="${HOT_WAIT_SECONDS:-70}"

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
  local uname="ced${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  if [ "$HTTP_STATUS" != "200" ]; then
    echo "FATAL: registration of $uname failed (HTTP $HTTP_STATUS) — $HTTP_BODY" >&2
    exit 1
  fi
  echo "$HTTP_BODY" | jq -r .accessToken
}

################################################################################
echo "=== Phase A: seed owner/other, a community, and content ==="
################################################################################

OWNER=$(register owner)
OWNER_NAME="ced${RUN}owner"
OTHER=$(register other)
OTHER_NAME="ced${RUN}other"

COMM="cedcomm${RUN}"
req POST /r "{\"name\":\"$COMM\",\"description\":\"edit/delete seed\",\"type\":\"public\"}" -H "Authorization: Bearer $OWNER"
expect_status "create the seed community" "200" "$HTTP_STATUS" "-"

req POST "/r/$COMM/submit" '{"kind":"text","title":"edit me","body":"original body"}' \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: ced-${RUN}-text"
expect_status "owner submits the text post to be edited" "200" "$HTTP_STATUS" "-"
P_TEXT=$(echo "$HTTP_BODY" | jq -r .id)

req POST "/r/$COMM/submit" '{"kind":"link","title":"a link","url":"https://example.com/ced"}' \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: ced-${RUN}-link"
expect_status "owner submits a link post" "200" "$HTTP_STATUS" "-"
P_LINK=$(echo "$HTTP_BODY" | jq -r .id)

req POST "/r/$COMM/submit" '{"kind":"text","title":"to be deleted","body":"gone soon"}' \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: ced-${RUN}-del"
expect_status "owner submits a text post to be deleted" "200" "$HTTP_STATUS" "-"
P_DEL=$(echo "$HTTP_BODY" | jq -r .id)

req POST "/r/$COMM/submit" '{"kind":"text","title":"to be mod-removed","body":"x"}' \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: ced-${RUN}-rem"
expect_status "owner submits a text post to be mod-removed" "200" "$HTTP_STATUS" "-"
P_REM=$(echo "$HTTP_BODY" | jq -r .id)

req POST /api/comment "{\"postId\":\"$P_TEXT\",\"body\":\"owner top comment\"}" -H "Authorization: Bearer $OWNER"
expect_status "owner comments on the text post" "200" "$HTTP_STATUS" "-"
C1=$(echo "$HTTP_BODY" | jq -r .id)

req POST /api/comment "{\"postId\":\"$P_TEXT\",\"parentId\":\"$C1\",\"body\":\"reply from other\"}" -H "Authorization: Bearer $OTHER"
expect_status "other replies under the owner's comment" "200" "$HTTP_STATUS" "-"
R1=$(echo "$HTTP_BODY" | jq -r .id)

req POST /api/comment "{\"postId\":\"$P_TEXT\",\"body\":\"comment to be mod-removed\"}" -H "Authorization: Bearer $OWNER"
expect_status "owner comments again (to be mod-removed later)" "200" "$HTTP_STATUS" "-"
C_REM=$(echo "$HTTP_BODY" | jq -r .id)

req POST "/r/$COMM/submit" '{"kind":"text","title":"other posts this","body":"hi"}' \
  -H "Authorization: Bearer $OTHER" -H "Idempotency-Key: ced-${RUN}-other"
expect_status "other submits a post (for the non-author delete check)" "200" "$HTTP_STATUS" "-"
P_OTHER=$(echo "$HTTP_BODY" | jq -r .id)

################################################################################
echo "=== Phase B: post edit (author-only, text-only, sanitized) ==="
################################################################################

req PATCH "/r/$COMM/posts/$P_TEXT" '{"body":"edited body"}' -H "Authorization: Bearer $OWNER"
expect_status "author edits their own text post's body" "200" "$HTTP_STATUS" "-"
expect_eq "the edited body is returned" "edited body" "$(echo "$HTTP_BODY" | jq -r .body)"
EDITED_AT=$(echo "$HTTP_BODY" | jq -r .editedAt)
if [ "$EDITED_AT" != "null" ] && [ -n "$EDITED_AT" ]; then
  record PASS "editedAt is set after an edit" "got $EDITED_AT"
else
  record FAIL "editedAt is set after an edit" "got '$EDITED_AT'"
fi
expect_eq "the title is unchanged by a body edit" "edit me" "$(echo "$HTTP_BODY" | jq -r .title)"

req PATCH "/r/$COMM/posts/$P_TEXT" '{"body":"hijacked"}' -H "Authorization: Bearer $OTHER"
expect_status "a non-author cannot edit the post" "403" "$HTTP_STATUS" "-"

req PATCH "/r/$COMM/posts/$(uuidgen)" '{"body":"nope"}' -H "Authorization: Bearer $OWNER"
expect_status "editing a nonexistent post is 404" "404" "$HTTP_STATUS" "-"

req PATCH "/r/$COMM/posts/$P_LINK" '{"body":"nope"}' -H "Authorization: Bearer $OWNER"
expect_status "editing a link post's body is rejected (only text posts have a body)" "400" "$HTTP_STATUS" "-"

req PATCH "/r/$COMM/posts/$P_TEXT" '{"body":"   "}' -H "Authorization: Bearer $OWNER"
expect_status "a whitespace-only body is rejected" "400" "$HTTP_STATUS" "-"

req PATCH "/r/$COMM/posts/$P_TEXT" '{"body":"<script>alert(1)</script>safe"}' -H "Authorization: Bearer $OWNER"
expect_status "an edit with a script tag is accepted" "200" "$HTTP_STATUS" "-"
SCRIPT_HITS=$(echo "$HTTP_BODY" | jq -r .body | grep -c '<script>' || true)
expect_eq "the stored edit has no raw script tag (sanitized like create)" "0" "$SCRIPT_HITS"

################################################################################
echo "=== Phase C: comment edit (author-only) ==="
################################################################################

req PATCH "/api/comment/$C1" '{"body":"edited owner comment"}' -H "Authorization: Bearer $OWNER"
expect_status "author edits their own comment" "200" "$HTTP_STATUS" "-"
expect_eq "the edited comment body is returned" "edited owner comment" "$(echo "$HTTP_BODY" | jq -r .body)"
if [ "$(echo "$HTTP_BODY" | jq -r .editedAt)" != "null" ]; then
  record PASS "editedAt is set on a comment edit" "$(echo "$HTTP_BODY" | jq -r .editedAt)"
else
  record FAIL "editedAt is set on a comment edit" "got null"
fi

req PATCH "/api/comment/$C1" '{"body":"hijacked"}' -H "Authorization: Bearer $OTHER"
expect_status "a non-author cannot edit the comment" "403" "$HTTP_STATUS" "-"

req PATCH "/api/comment/$C1" '{"body":""}' -H "Authorization: Bearer $OWNER"
expect_status "a blank comment edit is rejected" "400" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase D: deleting a comment that has replies keeps the subtree as a tombstone ==="
################################################################################

req DELETE "/api/comment/$C1" "" -H "Authorization: Bearer $OTHER"
expect_status "a non-author cannot delete the comment" "403" "$HTTP_STATUS" "-"

req DELETE "/api/comment/$C1" "" -H "Authorization: Bearer $OWNER"
expect_status "author deletes their own comment (which has a reply)" "200" "$HTTP_STATUS" "-"

expect_eq "the comment row survives with deleted=true and a wiped body" "true|[deleted]" \
  "$(psql_c "SELECT deleted || '|' || body FROM comments WHERE id='$C1'")"
expect_eq "the reply row survives, still parented to the deleted comment" "$C1" \
  "$(psql_c "SELECT parent_id FROM comments WHERE id='$R1'")"

req GET "/r/$COMM/comments/$P_TEXT" ""
expect_status "post detail still loads with the deleted comment in the tree" "200" "$HTTP_STATUS" "-"
TOMB=$(echo "$HTTP_BODY" | jq -c --arg id "$C1" '.comments.data.children[] | select(.data.id == $id) | .data')
expect_eq "tombstone body is [deleted]" "[deleted]" "$(echo "$TOMB" | jq -r .body)"
expect_eq "tombstone authorUsername is null" "null" "$(echo "$TOMB" | jq -r .authorUsername)"
expect_eq "tombstone deleted flag is true" "true" "$(echo "$TOMB" | jq -r .deleted)"
expect_eq "the reply still renders under the tombstone" "$R1" "$(echo "$TOMB" | jq -r '.replies[0].id')"

req DELETE "/api/comment/$C1" "" -H "Authorization: Bearer $OWNER"
expect_status "a second delete of the same comment is idempotent (200)" "200" "$HTTP_STATUS" "-"

req PATCH "/api/comment/$C1" '{"body":"back from the dead"}' -H "Authorization: Bearer $OWNER"
expect_status "a deleted comment cannot be edited (404)" "404" "$HTTP_STATUS" "-"

req POST /api/comment "{\"postId\":\"$P_TEXT\",\"parentId\":\"$C1\",\"body\":\"reply to a tombstone\"}" -H "Authorization: Bearer $OTHER"
expect_status "a deleted comment cannot be replied to (404)" "404" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase E: post delete (tombstone) ==="
################################################################################

req POST /api/comment "{\"postId\":\"$P_DEL\",\"body\":\"other's comment on the doomed post\"}" -H "Authorization: Bearer $OTHER"
expect_status "other comments on the post that will be deleted" "200" "$HTTP_STATUS" "-"
C_ON_DEL=$(echo "$HTTP_BODY" | jq -r .id)

req GET "/r/$COMM/hot" ""
if echo "$HTTP_BODY" | jq -e --arg id "$P_DEL" '.data.children[] | select(.data.id == $id)' >/dev/null 2>&1; then
  record PASS "the post is on anonymous /hot before deletion (cache pre-warmed)" "found"
else
  record FAIL "the post is on anonymous /hot before deletion (cache pre-warmed)" "not found"
fi

req DELETE "/r/$COMM/posts/$P_DEL" "" -H "Authorization: Bearer $OTHER"
expect_status "a non-author cannot delete the post" "403" "$HTTP_STATUS" "-"

req DELETE "/r/$COMM/posts/$P_DEL" "" -H "Authorization: Bearer $OWNER"
expect_status "author deletes their own post" "200" "$HTTP_STATUS" "-"

req DELETE "/r/$COMM/posts/$P_DEL" "" -H "Authorization: Bearer $OWNER"
expect_status "a second delete of the same post is idempotent (200)" "200" "$HTTP_STATUS" "-"

expect_eq "the post row is tombstoned: deleted, title wiped, body/url/media/pin cleared" \
  "true|[deleted]|true|true|true|false" \
  "$(psql_c "SELECT deleted || '|' || title || '|' || (body IS NULL) || '|' || (url IS NULL) || '|' || (media_id IS NULL) || '|' || pinned FROM posts WHERE id='$P_DEL'")"

req GET "/r/$COMM/new" ""
if echo "$HTTP_BODY" | jq -e --arg id "$P_DEL" '.data.children[] | select(.data.id == $id)' >/dev/null; then
  record FAIL "the deleted post drops out of the community's /new listing" "still present"
else
  record PASS "the deleted post drops out of the community's /new listing" "absent"
fi

req GET "/user/$OWNER_NAME/submitted" ""
if echo "$HTTP_BODY" | jq -e --arg id "$P_DEL" '.data.children[] | select(.data.id == $id)' >/dev/null; then
  record FAIL "the deleted post drops out of the author's submitted tab" "still present"
else
  record PASS "the deleted post drops out of the author's submitted tab" "absent"
fi

req GET "/r/$COMM/comments/$P_DEL" ""
expect_status "the deleted post's detail page still loads" "200" "$HTTP_STATUS" "-"
expect_eq "the detail page shows the post title as [deleted]" "[deleted]" "$(echo "$HTTP_BODY" | jq -r .post.title)"
expect_eq "the detail page's post body is null" "null" "$(echo "$HTTP_BODY" | jq -r .post.body)"
expect_eq "the detail page's authorUsername is null" "null" "$(echo "$HTTP_BODY" | jq -r .post.authorUsername)"
COMMENT_SURVIVED=$(echo "$HTTP_BODY" | jq -r --arg id "$C_ON_DEL" '[.comments.data.children[] | select(.data.id == $id)] | length')
expect_eq "the comment thread survives the post's deletion" "1" "$COMMENT_SURVIVED"

req PATCH "/r/$COMM/posts/$P_DEL" '{"body":"resurrect"}' -H "Authorization: Bearer $OWNER"
expect_status "a deleted post cannot be edited (404)" "404" "$HTTP_STATUS" "-"

req POST /api/comment "{\"postId\":\"$P_DEL\",\"body\":\"reply to a tombstoned post\"}" -H "Authorization: Bearer $OTHER"
expect_status "a deleted post cannot be replied to (404)" "404" "$HTTP_STATUS" "-"

echo "INFO  waiting up to ${HOT_WAIT_SECONDS}s for the /hot cache TTL to elapse..."
HOT_GONE=0
WAITED=0
while [ "$WAITED" -lt "$HOT_WAIT_SECONDS" ]; do
  req GET "/r/$COMM/hot" ""
  if ! echo "$HTTP_BODY" | jq -e --arg id "$P_DEL" '.data.children[] | select(.data.id == $id)' >/dev/null 2>&1; then
    HOT_GONE=1
    break
  fi
  sleep 2
  WAITED=$((WAITED+2))
done
if [ "$HOT_GONE" = "1" ]; then
  record PASS "the deleted post leaves anonymous /hot once the cache TTL elapses" "gone after ~${WAITED}s"
else
  record FAIL "the deleted post leaves anonymous /hot once the cache TTL elapses" "still present after ${HOT_WAIT_SECONDS}s"
fi

################################################################################
echo "=== Phase F: moderator-removed content is immutable for its author ==="
################################################################################

req POST "/r/$COMM/mod/remove/post/$P_REM" '{"reason":"ced test"}' -H "Authorization: Bearer $OWNER"
expect_status "the moderator removes a post" "200" "$HTTP_STATUS" "-"

req PATCH "/r/$COMM/posts/$P_REM" '{"body":"sneaky"}' -H "Authorization: Bearer $OWNER"
expect_status "the author cannot edit a mod-removed post" "403" "$HTTP_STATUS" "-"

req DELETE "/r/$COMM/posts/$P_REM" "" -H "Authorization: Bearer $OWNER"
expect_status "the author cannot delete a mod-removed post" "403" "$HTTP_STATUS" "-"

req POST "/r/$COMM/mod/remove/comment/$C_REM" '{"reason":"ced test"}' -H "Authorization: Bearer $OWNER"
expect_status "the moderator removes a comment" "200" "$HTTP_STATUS" "-"

req PATCH "/api/comment/$C_REM" '{"body":"sneaky"}' -H "Authorization: Bearer $OWNER"
expect_status "the author cannot edit a mod-removed comment" "403" "$HTTP_STATUS" "-"

req DELETE "/api/comment/$C_REM" "" -H "Authorization: Bearer $OWNER"
expect_status "the author cannot delete a mod-removed comment" "403" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase G: deleted comments leave the author's profile, live ones stay ==="
################################################################################

req GET "/user/$OWNER_NAME/comments" ""
if echo "$HTTP_BODY" | jq -e --arg id "$C1" '.data.children[] | select(.data.id == $id)' >/dev/null; then
  record FAIL "the deleted comment is absent from the owner's comments tab" "still present"
else
  record PASS "the deleted comment is absent from the owner's comments tab" "absent"
fi

req GET "/user/$OTHER_NAME/comments" ""
if echo "$HTTP_BODY" | jq -e --arg id "$R1" '.data.children[] | select(.data.id == $id)' >/dev/null; then
  record PASS "a live reply by another user still shows on their comments tab (control)" "present"
else
  record FAIL "a live reply by another user still shows on their comments tab (control)" "absent"
fi

################################################################################
echo
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
