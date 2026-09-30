#!/usr/bin/env bash
# Seeds a small dataset through the live Phase 4 REST API (never direct SQL for content creation, same
# rule as Phase 1-3's scripts) and re-verifies notifications/saved+hidden items/media uploads/account
# settings & deletion end to end: reply/mention notification fan-out via the outbox worker (including the
# notifications-table partition fix — this is the same bug class already found in reports/
# moderation_actions, so the very first insert here is itself a regression check), self-notification
# suppression, mark-read, save/unsave and hide/unhide for both posts and comments with per-viewer scoping
# (including the unauthenticated-request bypass), account prefs
# get/patch, account deletion (wrong-password rejection, anonymization, post-deletion login rejection),
# the full media pipeline (presigned upload -> real PUT -> complete -> async processing -> attached media
# view on post reads) for both an image and a real small video, kind-conditional post validation,
# cross-user media ownership rejection, oversized/wrong-content-type upload rejection, and a corrupted
# upload's bounded retry -> terminal failed state. Prints PASS/FAIL with the real observed value for
# every check. Data is left in the dev database afterward.
#
# Prereqs: docker compose stack up (including s3mock), app running on $BASE, ffmpeg/ffprobe on PATH.

set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5434}"
PGUSER="${PGUSER:-app}"
PGDATABASE="${PGDATABASE:-redditclone}"
export PGPASSWORD="${PGPASSWORD:-devpassword}"

RUN=$(date +%s | tail -c 6)   # short run-scoped suffix so re-runs never collide with prior seed data
PASSWORD="Sup3rSecret!1"
OUTBOX_WAIT=3     # OutboxWorker ticks every 2s
IMAGE_WAIT=4      # ImageProcessingWorker ticks every 2s
VIDEO_WAIT=13     # VideoProcessingWorker ticks every 10s, plus real ffmpeg transcode time

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
  local uname="p4${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

################################################################################
echo "=== Phase A: seed users, community, real test media files ==="
################################################################################

OWNER=$(register owner)
COMMENTER=$(register commenter)
OWNER_ID=$(psql_c "SELECT id FROM users WHERE username='p4${RUN}owner'")
COMMENTER_ID=$(psql_c "SELECT id FROM users WHERE username='p4${RUN}commenter'")

req POST /r "{\"name\":\"p4comm${RUN}\",\"description\":\"phase 4 seed\"}" -H "Authorization: Bearer $OWNER"
COMM="p4comm${RUN}"
expect_status "create seed community" "200" "$HTTP_STATUS" "body=$HTTP_BODY"

TMPDIR=$(mktemp -d)
ffmpeg -y -loglevel error -f lavfi -i "testsrc=size=320x240:duration=1:rate=1" -frames:v 1 "$TMPDIR/test.jpg"
ffmpeg -y -loglevel error -f lavfi -i "testsrc=size=320x240:duration=2:rate=10" -f lavfi -i "sine=frequency=440:duration=2" \
  -c:v libx264 -c:a aac -t 2 "$TMPDIR/test.mp4"
IMG_BYTES=$(stat -f%z "$TMPDIR/test.jpg" 2>/dev/null || stat -c%s "$TMPDIR/test.jpg")
VID_BYTES=$(stat -f%z "$TMPDIR/test.mp4" 2>/dev/null || stat -c%s "$TMPDIR/test.mp4")

################################################################################
echo "=== Phase B: notifications (reply/post_reply/mention fan-out, self-notification suppression, mark-read) ==="
################################################################################

req POST "/r/$COMM/submit" "{\"kind\":\"text\",\"title\":\"seed post\",\"body\":\"seed body\"}" \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: p4-post-${RUN}"
POST_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "submit seed text post" "200" "$HTTP_STATUS" "id=$POST_ID"

# Confirm the notifications table's partition fix actually works before relying on it anywhere else —
# same verification-order lesson as the original reports/moderation_actions partition bug in Phase 3.
PARTITION_COUNT=$(psql_c "SELECT count(*) FROM pg_inherits WHERE inhparent = 'notifications'::regclass")
if [ "$PARTITION_COUNT" -gt 0 ]; then
  record PASS "notifications table has partitions (not the Phase 3 partition bug again)" "partition_count=$PARTITION_COUNT"
else
  record FAIL "notifications table has partitions (not the Phase 3 partition bug again)" "partition_count=$PARTITION_COUNT"
fi

req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"nice post u/p4${RUN}owner!\"}" -H "Authorization: Bearer $COMMENTER"
REPLY_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_status "commenter replies to owner's post, mentioning owner" "200" "$HTTP_STATUS" "id=$REPLY_ID"

req POST /api/comment "{\"postId\":\"$POST_ID\",\"body\":\"replying to myself\"}" -H "Authorization: Bearer $OWNER"
expect_status "owner replies to their own post" "200" "$HTTP_STATUS" "-"

sleep "$OUTBOX_WAIT"
req GET /api/notifications "" -H "Authorization: Bearer $OWNER"
NOTIF_COUNT=$(echo "$HTTP_BODY" | jq 'length')
NOTIF_TYPES=$(echo "$HTTP_BODY" | jq -r '[.[].type] | sort | join(",")')
expect_eq "owner has exactly 2 notifications (post_reply + mention, no self-notify)" "2" "$NOTIF_COUNT"
expect_eq "notification types are post_reply and mention" "mention,post_reply" "$NOTIF_TYPES"

NOTIF_ID=$(echo "$HTTP_BODY" | jq -r '.[0].id')
req POST "/api/notifications/$NOTIF_ID/read" "" -H "Authorization: Bearer $OWNER"
expect_status "mark notification read" "200" "$HTTP_STATUS" "-"
READ_AT=$(psql_c "SELECT read_at IS NOT NULL FROM notifications WHERE id='$NOTIF_ID'")
expect_eq "notification's read_at is set after marking read" "t" "$READ_AT"

################################################################################
echo "=== Phase D: saved/hidden items, per-viewer scoping (posts and comments) ==="
################################################################################

req POST /api/save "{\"targetType\":\"post\",\"targetId\":\"$POST_ID\"}" -H "Authorization: Bearer $OWNER"
expect_status "save a post" "200" "$HTTP_STATUS" "-"
req POST /api/save "{\"targetType\":\"post\",\"targetId\":\"$POST_ID\"}" -H "Authorization: Bearer $OWNER"
expect_status "re-saving the same post is idempotent" "200" "$HTTP_STATUS" "-"
req DELETE /api/save "{\"targetType\":\"post\",\"targetId\":\"$POST_ID\"}" -H "Authorization: Bearer $OWNER"
expect_status "unsave a post" "200" "$HTTP_STATUS" "-"

req POST /api/hide "{\"targetType\":\"post\",\"targetId\":\"$POST_ID\"}" -H "Authorization: Bearer $OWNER"
expect_status "hide a post" "200" "$HTTP_STATUS" "-"
req GET "/r/$COMM/new" "" -H "Authorization: Bearer $OWNER"
OWNER_SEES=$(echo "$HTTP_BODY" | jq '[.data.children[].data.id] | index("'"$POST_ID"'") != null')
expect_eq "hiding user no longer sees the hidden post in /new" "false" "$OWNER_SEES"
req GET "/r/$COMM/new" ""
ANON_SEES=$(echo "$HTTP_BODY" | jq '[.data.children[].data.id] | index("'"$POST_ID"'") != null')
expect_eq "an unauthenticated request still sees the hidden post (hide is per-user)" "true" "$ANON_SEES"
req DELETE /api/hide "{\"targetType\":\"post\",\"targetId\":\"$POST_ID\"}" -H "Authorization: Bearer $OWNER"
expect_status "unhide the post" "200" "$HTTP_STATUS" "-"

req POST /api/hide "{\"targetType\":\"comment\",\"targetId\":\"$REPLY_ID\"}" -H "Authorization: Bearer $OWNER"
expect_status "hide a comment" "200" "$HTTP_STATUS" "-"
req GET "/r/$COMM/comments/$POST_ID" "" -H "Authorization: Bearer $OWNER"
OWNER_COMMENT_COUNT=$(echo "$HTTP_BODY" | jq '.comments | length')
req GET "/r/$COMM/comments/$POST_ID" ""
ANON_COMMENT_COUNT=$(echo "$HTTP_BODY" | jq '.comments | length')
if [ "$ANON_COMMENT_COUNT" -gt "$OWNER_COMMENT_COUNT" ]; then
  record PASS "hiding a comment removes it only for the hiding viewer" "anon=$ANON_COMMENT_COUNT owner=$OWNER_COMMENT_COUNT"
else
  record FAIL "hiding a comment removes it only for the hiding viewer" "anon=$ANON_COMMENT_COUNT owner=$OWNER_COMMENT_COUNT"
fi
req DELETE /api/hide "{\"targetType\":\"comment\",\"targetId\":\"$REPLY_ID\"}" -H "Authorization: Bearer $OWNER"
expect_status "unhide the comment" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase E: account settings (prefs) ==="
################################################################################

req GET /api/v1/me/prefs "" -H "Authorization: Bearer $COMMENTER"
DEFAULT_BLUR=$(echo "$HTTP_BODY" | jq -r .nsfwBlur)
expect_eq "prefs default nsfwBlur is true" "true" "$DEFAULT_BLUR"

req PATCH /api/v1/me/prefs '{"nsfwBlur":false,"privacyPrefs":{"showEmail":false}}' -H "Authorization: Bearer $COMMENTER"
expect_status "patch prefs" "200" "$HTTP_STATUS" "body=$HTTP_BODY"
req GET /api/v1/me/prefs "" -H "Authorization: Bearer $COMMENTER"
UPDATED_BLUR=$(echo "$HTTP_BODY" | jq -r .nsfwBlur)
expect_eq "prefs reflect the update" "false" "$UPDATED_BLUR"

################################################################################
echo "=== Phase F: media — image upload -> process -> attach ==="
################################################################################

req POST /api/media/upload-url "{\"filename\":\"test.jpg\",\"contentType\":\"image/jpeg\",\"byteSize\":$IMG_BYTES}" -H "Authorization: Bearer $OWNER"
IMG_MEDIA_ID=$(echo "$HTTP_BODY" | jq -r .mediaId)
IMG_UPLOAD_URL=$(echo "$HTTP_BODY" | jq -r .uploadUrl)
expect_status "request image upload URL" "200" "$HTTP_STATUS" "mediaId=$IMG_MEDIA_ID"

IMG_PUT_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$IMG_UPLOAD_URL" -H "Content-Type: image/jpeg" --data-binary "@$TMPDIR/test.jpg")
expect_status "PUT real JPEG straight to storage" "200" "$IMG_PUT_STATUS" "-"

req POST "/api/media/$IMG_MEDIA_ID/complete" "" -H "Authorization: Bearer $OWNER"
expect_status "complete image upload (headObject check passes)" "200" "$HTTP_STATUS" "-"

sleep "$IMAGE_WAIT"
IMG_STATUS_DB=$(psql_c "SELECT processing_status FROM media WHERE id='$IMG_MEDIA_ID'")
expect_eq "ImageProcessingWorker marks the image ready" "ready" "$IMG_STATUS_DB"

req POST "/r/$COMM/submit" "{\"kind\":\"image\",\"title\":\"seed image\",\"mediaId\":\"$IMG_MEDIA_ID\"}" \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: p4-img-${RUN}"
IMG_POST_ID=$(echo "$HTTP_BODY" | jq -r .id)
IMG_DISPLAY_URL=$(echo "$HTTP_BODY" | jq -r .media.displayUrl)
expect_status "submit image post with a ready media id" "200" "$HTTP_STATUS" "id=$IMG_POST_ID"
IMG_URL_STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$IMG_DISPLAY_URL")
expect_status "the returned display image URL actually resolves" "200" "$IMG_URL_STATUS" "url=$IMG_DISPLAY_URL"

################################################################################
echo "=== Phase G: media — video upload -> ffmpeg process -> attach ==="
################################################################################

req POST /api/media/upload-url "{\"filename\":\"test.mp4\",\"contentType\":\"video/mp4\",\"byteSize\":$VID_BYTES}" -H "Authorization: Bearer $OWNER"
VID_MEDIA_ID=$(echo "$HTTP_BODY" | jq -r .mediaId)
VID_UPLOAD_URL=$(echo "$HTTP_BODY" | jq -r .uploadUrl)
expect_status "request video upload URL" "200" "$HTTP_STATUS" "mediaId=$VID_MEDIA_ID"
VID_MEDIA_TYPE=$(psql_c "SELECT media_type FROM media WHERE id='$VID_MEDIA_ID'")
expect_eq "video content-type routes to media_type=video" "video" "$VID_MEDIA_TYPE"

VID_PUT_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$VID_UPLOAD_URL" -H "Content-Type: video/mp4" --data-binary "@$TMPDIR/test.mp4")
expect_status "PUT real MP4 straight to storage" "200" "$VID_PUT_STATUS" "-"
req POST "/api/media/$VID_MEDIA_ID/complete" "" -H "Authorization: Bearer $OWNER"
expect_status "complete video upload" "200" "$HTTP_STATUS" "-"

sleep "$VIDEO_WAIT"
VID_STATUS_DB=$(psql_c "SELECT processing_status FROM media WHERE id='$VID_MEDIA_ID'")
VID_DURATION=$(psql_c "SELECT duration_seconds FROM media WHERE id='$VID_MEDIA_ID'")
expect_eq "VideoProcessingWorker (real ffmpeg) marks the video ready" "ready" "$VID_STATUS_DB"
expect_eq "ffprobe-extracted duration matches the 2-second source clip" "2.000000" "$VID_DURATION"

req POST "/r/$COMM/submit" "{\"kind\":\"video\",\"title\":\"seed video\",\"mediaId\":\"$VID_MEDIA_ID\"}" \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: p4-vid-${RUN}"
VID_DISPLAY_URL=$(echo "$HTTP_BODY" | jq -r .media.displayUrl)
expect_status "submit video post with a ready media id" "200" "$HTTP_STATUS" "-"
VID_URL_STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$VID_DISPLAY_URL")
expect_status "the returned display video URL actually resolves" "200" "$VID_URL_STATUS" "url=$VID_DISPLAY_URL"

################################################################################
echo "=== Phase H: media/post validation and failure paths ==="
################################################################################

req POST "/r/$COMM/submit" '{"kind":"image","title":"no media"}' -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: p4-badimg-${RUN}"
expect_status "kind=image with no mediaId is rejected" "400" "$HTTP_STATUS" "-"
req POST "/r/$COMM/submit" '{"kind":"link","title":"no url"}' -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: p4-badlink-${RUN}"
expect_status "kind=link with no url is rejected" "400" "$HTTP_STATUS" "-"
req POST "/r/$COMM/submit" '{"kind":"text","title":"no body"}' -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: p4-badtext-${RUN}"
expect_status "kind=text with no body is rejected" "400" "$HTTP_STATUS" "-"

req POST "/r/$COMM/submit" "{\"kind\":\"image\",\"title\":\"stolen\",\"mediaId\":\"$IMG_MEDIA_ID\"}" \
  -H "Authorization: Bearer $COMMENTER" -H "Idempotency-Key: p4-foreign-${RUN}"
expect_status "using another user's mediaId is rejected" "403" "$HTTP_STATUS" "-"

req POST "/r/$COMM/submit" '{"kind":"image","title":"bogus","mediaId":"00000000-0000-7000-0000-000000000099"}' \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: p4-bogus-${RUN}"
expect_status "a nonexistent mediaId is rejected" "404" "$HTTP_STATUS" "-"

# The idempotency key claimed above must be released on the media-validation failure, not poisoned —
# retrying the SAME key with valid data should succeed rather than 404 "post not found".
req POST "/r/$COMM/submit" '{"kind":"text","title":"now valid","body":"b"}' \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: p4-bogus-${RUN}"
expect_status "retrying the same idempotency key after a media rejection succeeds (key not poisoned)" "200" "$HTTP_STATUS" "body=$HTTP_BODY"

req POST /api/media/upload-url '{"filename":"huge.jpg","contentType":"image/jpeg","byteSize":999999999999}' -H "Authorization: Bearer $OWNER"
expect_status "an oversized upload-url request is rejected" "400" "$HTTP_STATUS" "-"
req POST /api/media/upload-url '{"filename":"file.exe","contentType":"application/octet-stream","byteSize":1000}' -H "Authorization: Bearer $OWNER"
expect_status "an unsupported content-type is rejected" "400" "$HTTP_STATUS" "-"

echo "not a real jpeg" > "$TMPDIR/corrupt.jpg"
req POST /api/media/upload-url '{"filename":"corrupt.jpg","contentType":"image/jpeg","byteSize":20}' -H "Authorization: Bearer $OWNER"
CORRUPT_MEDIA_ID=$(echo "$HTTP_BODY" | jq -r .mediaId)
CORRUPT_UPLOAD_URL=$(echo "$HTTP_BODY" | jq -r .uploadUrl)
curl -s -o /dev/null -X PUT "$CORRUPT_UPLOAD_URL" -H "Content-Type: image/jpeg" --data-binary "@$TMPDIR/corrupt.jpg"
req POST "/api/media/$CORRUPT_MEDIA_ID/complete" "" -H "Authorization: Bearer $OWNER"
expect_status "complete accepts the corrupt-but-present upload (headObject only checks existence)" "200" "$HTTP_STATUS" "-"

echo "  (waiting ~12s for bounded retries across ImageProcessingWorker ticks...)"
sleep 12
CORRUPT_STATUS_DB=$(psql_c "SELECT processing_status FROM media WHERE id='$CORRUPT_MEDIA_ID'")
CORRUPT_ATTEMPTS=$(psql_c "SELECT attempt_count FROM media WHERE id='$CORRUPT_MEDIA_ID'")
expect_eq "a corrupted upload reaches terminal 'failed' after bounded retries, not stuck" "failed" "$CORRUPT_STATUS_DB"
record INFO "corrupt upload attempt_count" "attempts=$CORRUPT_ATTEMPTS (expected 5)"

################################################################################
echo "=== Phase I: account deletion ==="
################################################################################

DELETE_ME=$(register deleteme)
req DELETE /api/v1/me '{"password":"wrongpassword"}' -H "Authorization: Bearer $DELETE_ME"
expect_status "deleting with the wrong password is rejected" "401" "$HTTP_STATUS" "-"
req POST /api/v1/access_token "{\"username\":\"p4${RUN}deleteme\",\"password\":\"$PASSWORD\"}"
expect_status "login still works after a failed delete attempt" "200" "$HTTP_STATUS" "-"

req DELETE /api/v1/me "{\"password\":\"$PASSWORD\"}" -H "Authorization: Bearer $DELETE_ME"
expect_status "deleting with the correct password succeeds" "200" "$HTTP_STATUS" "-"
DELETED_STATUS=$(psql_c "SELECT status FROM users WHERE username LIKE 'deleted_%' AND id = (SELECT id FROM users WHERE email LIKE '%deleted.invalid' ORDER BY created_at DESC LIMIT 1)")
expect_eq "deleted account's status is 'deleted' in the database" "deleted" "$DELETED_STATUS"

req POST /api/v1/access_token "{\"username\":\"p4${RUN}deleteme\",\"password\":\"$PASSWORD\"}"
expect_status "login with the old (now-anonymized) username is rejected after deletion" "401" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase J: regression spot-check (Phase 2/3 features still work) ==="
################################################################################

req POST /api/vote "{\"targetType\":\"post\",\"targetId\":\"$POST_ID\",\"dir\":1}" -H "Authorization: Bearer $OWNER"
expect_status "voting still works" "200" "$HTTP_STATUS" "-"
req GET "/r/$COMM/hot" ""
expect_status "unauthenticated /hot (cache-eligible) still works" "200" "$HTTP_STATUS" "-"
req GET "/r/$COMM/hot" "" -H "Authorization: Bearer $OWNER"
expect_status "authenticated /hot (cache-bypassing, per-viewer hidden-items filter) still works" "200" "$HTTP_STATUS" "-"

rm -rf "$TMPDIR"

echo ""
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
