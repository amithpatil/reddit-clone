#!/usr/bin/env bash
# Seeds a small dataset through the live REST API and verifies backend feature 11 (gallery posts) end to
# end: uploading several images through the real presigned-upload flow, submitting a gallery post with a
# deliberately shuffled mediaIds order and confirming that exact order is persisted in post_media and
# returned via mediaItems, that the singular mediaId/media fields stay null for a gallery post, rejection
# of too-few (<2) and too-many (>20) images, rejection of a duplicate mediaId, rejection of a mediaId not
# owned by the caller (403) and a mediaId whose real type isn't "image" (400, a video), and that gallery
# posts show up correctly (with mediaItems attached) across the community feed and the author's profile
# listing — regression-guarding every other attachX method still running via attachAll. Prints PASS/FAIL
# with the real observed value for every check. Data is left in the dev database afterward.
#
# Prereqs: docker compose stack up (including s3mock), app running on $BASE, ffmpeg on PATH.

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
  local uname="gp${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  if [ "$HTTP_STATUS" != "200" ]; then
    echo "FATAL: registration of $uname failed (HTTP $HTTP_STATUS) — $HTTP_BODY" >&2
    exit 1
  fi
  echo "$HTTP_BODY" | jq -r .accessToken
}

TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT
ffmpeg -y -loglevel error -f lavfi -i "testsrc=size=320x240:duration=1:rate=1" -frames:v 1 "$TMPDIR/img.jpg"
ffmpeg -y -loglevel error -f lavfi -i "testsrc=size=320x240:duration=1:rate=1" \
  -c:v libx264 -c:a aac -t 1 "$TMPDIR/vid.mp4"
IMG_BYTES=$(stat -f%z "$TMPDIR/img.jpg" 2>/dev/null || stat -c%s "$TMPDIR/img.jpg")
VID_BYTES=$(stat -f%z "$TMPDIR/vid.mp4" 2>/dev/null || stat -c%s "$TMPDIR/vid.mp4")

upload_image() {
  local token="$1" filename="$2"
  req POST /api/media/upload-url "{\"filename\":\"$filename\",\"contentType\":\"image/jpeg\",\"byteSize\":$IMG_BYTES}" \
    -H "Authorization: Bearer $token"
  local mid url
  mid=$(echo "$HTTP_BODY" | jq -r .mediaId)
  url=$(echo "$HTTP_BODY" | jq -r .uploadUrl)
  curl -s -o /dev/null -X PUT "$url" -H "Content-Type: image/jpeg" --data-binary "@$TMPDIR/img.jpg"
  req POST "/api/media/$mid/complete" "" -H "Authorization: Bearer $token"
  echo "$mid"
}

################################################################################
echo "=== Phase A: seed owner/other and a community ==="
################################################################################

OWNER=$(register owner)
OWNER_NAME="gp${RUN}owner"
OTHER=$(register other)

COMM="galcomm${RUN}"
req POST /r "{\"name\":\"$COMM\",\"description\":\"gallery seed\",\"type\":\"public\"}" -H "Authorization: Bearer $OWNER"
expect_status "create the seed community" "200" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase B: upload 4 images as owner ==="
################################################################################

IMG1=$(upload_image "$OWNER" img1.jpg)
IMG2=$(upload_image "$OWNER" img2.jpg)
IMG3=$(upload_image "$OWNER" img3.jpg)
IMG4=$(upload_image "$OWNER" img4.jpg)
record INFO "uploaded 4 images" "$IMG1 $IMG2 $IMG3 $IMG4"

################################################################################
echo "=== Phase C: submit a gallery with a deliberately shuffled order ==="
################################################################################

req POST "/r/$COMM/submit" "{\"kind\":\"gallery\",\"title\":\"shuffled gallery\",\"mediaIds\":[\"$IMG3\",\"$IMG1\",\"$IMG4\",\"$IMG2\"]}" \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: gal-${RUN}-1"
expect_status "submit a 4-image gallery post" "200" "$HTTP_STATUS" "body=$HTTP_BODY"
POST_ID=$(echo "$HTTP_BODY" | jq -r .id)
expect_eq "response kind is gallery" "gallery" "$(echo "$HTTP_BODY" | jq -r .kind)"
expect_eq "response mediaId (singular) stays null" "null" "$(echo "$HTTP_BODY" | jq -r .mediaId)"
expect_eq "response media (singular) stays null" "null" "$(echo "$HTTP_BODY" | jq -r .media)"
expect_eq "response mediaItems has all 4 entries" "4" "$(echo "$HTTP_BODY" | jq '.mediaItems | length')"

DB_ORDER=$(psql_c "SELECT media_id FROM post_media WHERE post_id='$POST_ID' ORDER BY position")
EXPECTED_ORDER=$(printf '%s\n%s\n%s\n%s' "$IMG3" "$IMG1" "$IMG4" "$IMG2")
expect_eq "post_media rows preserve the exact submitted order" "$EXPECTED_ORDER" "$DB_ORDER"

req GET "/r/$COMM/comments/$POST_ID" ""
expect_status "fetch the gallery post detail" "200" "$HTTP_STATUS" "-"
expect_eq "detail view's mediaItems also has 4 entries" "4" "$(echo "$HTTP_BODY" | jq '.post.mediaItems | length')"

################################################################################
echo "=== Phase D: gallery size bounds (2-20) ==="
################################################################################

req POST "/r/$COMM/submit" '{"kind":"gallery","title":"too few","mediaIds":[]}' \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: gal-${RUN}-2"
expect_status "0 images is rejected" "400" "$HTTP_STATUS" "-"

req POST "/r/$COMM/submit" "{\"kind\":\"gallery\",\"title\":\"too few\",\"mediaIds\":[\"$IMG1\"]}" \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: gal-${RUN}-3"
expect_status "1 image is rejected" "400" "$HTTP_STATUS" "-"

TOO_MANY=$(for i in $(seq 1 21); do uuidgen; done | jq -R -s -c 'split("\n") | map(select(length > 0))')
req POST "/r/$COMM/submit" "{\"kind\":\"gallery\",\"title\":\"too many\",\"mediaIds\":$TOO_MANY}" \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: gal-${RUN}-4"
expect_status "21 images is rejected" "400" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase E: duplicate mediaId, not-owned mediaId, wrong media type ==="
################################################################################

req POST "/r/$COMM/submit" "{\"kind\":\"gallery\",\"title\":\"dup\",\"mediaIds\":[\"$IMG1\",\"$IMG1\"]}" \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: gal-${RUN}-5"
expect_status "a duplicate mediaId in the list is rejected" "400" "$HTTP_STATUS" "-"

OTHER_IMG=$(upload_image "$OTHER" other.jpg)
req POST "/r/$COMM/submit" "{\"kind\":\"gallery\",\"title\":\"stolen\",\"mediaIds\":[\"$IMG1\",\"$OTHER_IMG\"]}" \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: gal-${RUN}-6"
expect_status "a mediaId not owned by the caller is rejected" "403" "$HTTP_STATUS" "-"

req POST /api/media/upload-url "{\"filename\":\"vid.mp4\",\"contentType\":\"video/mp4\",\"byteSize\":$VID_BYTES}" \
  -H "Authorization: Bearer $OWNER"
VID_MEDIA_ID=$(echo "$HTTP_BODY" | jq -r .mediaId)
VID_UPLOAD_URL=$(echo "$HTTP_BODY" | jq -r .uploadUrl)
curl -s -o /dev/null -X PUT "$VID_UPLOAD_URL" -H "Content-Type: video/mp4" --data-binary "@$TMPDIR/vid.mp4"
req POST "/api/media/$VID_MEDIA_ID/complete" "" -H "Authorization: Bearer $OWNER"
req POST "/r/$COMM/submit" "{\"kind\":\"gallery\",\"title\":\"wrong type\",\"mediaIds\":[\"$IMG1\",\"$VID_MEDIA_ID\"]}" \
  -H "Authorization: Bearer $OWNER" -H "Idempotency-Key: gal-${RUN}-7"
expect_status "a video mediaId inside a gallery list is rejected" "400" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase F: gallery posts appear correctly in existing listings ==="
################################################################################

req GET "/r/$COMM/new" ""
expect_status "community /new feed loads" "200" "$HTTP_STATUS" "-"
FOUND_IN_FEED=$(echo "$HTTP_BODY" | jq --arg id "$POST_ID" '[.data.children[] | select(.data.id == $id)] | length')
expect_eq "the gallery post appears in /new" "1" "$FOUND_IN_FEED"
FEED_MEDIA_COUNT=$(echo "$HTTP_BODY" | jq --arg id "$POST_ID" '[.data.children[] | select(.data.id == $id)][0].data.mediaItems | length')
expect_eq "its mediaItems is attached in the feed listing too (never-N+1 attachAll regression check)" "4" "$FEED_MEDIA_COUNT"

req GET "/user/$OWNER_NAME/submitted" ""
expect_status "owner's submitted-posts tab loads" "200" "$HTTP_STATUS" "-"
FOUND_IN_PROFILE=$(echo "$HTTP_BODY" | jq --arg id "$POST_ID" '[.data.children[] | select(.data.id == $id)] | length')
expect_eq "the gallery post appears on the owner's profile" "1" "$FOUND_IN_PROFILE"

################################################################################
echo
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
