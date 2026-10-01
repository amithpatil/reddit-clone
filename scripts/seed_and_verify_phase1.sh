#!/usr/bin/env bash
# Seeds a small dataset through the live Phase 1 REST API (never direct SQL for creation), then
# re-verifies all four Phase 1 checkpoints from docs/PHASE_1_BUILD_LOG.md plus a happy-path and
# expected-failure-path for every other implemented endpoint. Prints PASS/FAIL with the real
# observed value for every check. Data is left in the dev database afterward.
#
# Prereqs: docker compose stack up, app running on $BASE (see application.yml for the port).

set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5434}"
PGUSER="${PGUSER:-app}"
PGDATABASE="${PGDATABASE:-redditclone}"
export PGPASSWORD="${PGPASSWORD:-devpassword}"

RUN=$(date +%s | tail -c 6)   # short run-scoped suffix so re-runs never collide with prior seed data
PASSWORD="Sup3rSecret!1"

PASS_COUNT=0
FAIL_COUNT=0
INFO_COUNT=0
declare -a RESULTS=()

psql_c() { psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -t -A -c "$1"; }

record() {
  local status="$1" desc="$2" detail="$3"
  RESULTS+=("${status}|${desc}|${detail}")
  case "$status" in
    PASS) PASS_COUNT=$((PASS_COUNT+1)); printf 'PASS  %-70s %s\n' "$desc" "$detail" ;;
    FAIL) FAIL_COUNT=$((FAIL_COUNT+1)); printf 'FAIL  %-70s %s\n' "$desc" "$detail" ;;
    INFO) INFO_COUNT=$((INFO_COUNT+1)); printf 'INFO  %-70s %s\n' "$desc" "$detail" ;;
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

# request helper: sets HTTP_STATUS and HTTP_BODY globals
req() {
  local method="$1" path="$2" data="${3:-}" ; shift 3 || true
  local resp
  resp=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" -H 'Content-Type: application/json' \
    ${data:+-d "$data"} "$@")
  HTTP_STATUS=$(echo "$resp" | tail -n1)
  HTTP_BODY=$(echo "$resp" | sed '$d')
}

echo "=== Baseline table counts (before seeding) ==="
BASELINE=$(psql_c "SELECT 'users',count(*) FROM users UNION ALL SELECT 'communities',count(*) FROM communities UNION ALL SELECT 'memberships',count(*) FROM memberships UNION ALL SELECT 'posts',count(*) FROM posts UNION ALL SELECT 'comments',count(*) FROM comments;")
echo "$BASELINE"
echo ""

################################################################################
echo "=== Phase A: seeding via the live API (run suffix: $RUN) ==="
################################################################################

declare -a TOKENS=()
declare -a USERNAMES=()
N_USERS=18
for i in $(seq 1 $N_USERS); do
  uname="seed${RUN}u${i}"
  email="seed${RUN}u${i}@example.com"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$email\",\"password\":\"$PASSWORD\"}"
  if [ "$HTTP_STATUS" = "200" ]; then
    TOKENS[$i]=$(echo "$HTTP_BODY" | jq -r .accessToken)
    USERNAMES[$i]="$uname"
  else
    echo "FATAL: failed to register $uname -> $HTTP_STATUS $HTTP_BODY" >&2
    exit 1
  fi
done
record PASS "seed: register $N_USERS users" "all returned 200 with accessToken"

declare -a COMM_NAMES=()
N_COMMS=4
for i in $(seq 1 $N_COMMS); do
  cname="seedcomm${RUN}c${i}"
  req POST /r "{\"name\":\"$cname\",\"description\":\"seed community $i\"}" -H "Authorization: Bearer ${TOKENS[$i]}"
  if [ "$HTTP_STATUS" = "200" ]; then
    COMM_NAMES[$i]="$cname"
  else
    echo "FATAL: failed to create community $cname -> $HTTP_STATUS $HTTP_BODY" >&2
    exit 1
  fi
done
record PASS "seed: create $N_COMMS communities" "all returned 200"

# Spread the remaining users across communities 3 and 4 as ordinary subscribers (community 1 is
# reserved for the dedicated concurrency-burst test below; community 2 for the pagination test).
for i in $(seq 5 $N_USERS); do
  target=$(( (i % 2 == 0) ? 3 : 4 ))
  req POST "/r/${COMM_NAMES[$target]}/subscribe" "" -H "Authorization: Bearer ${TOKENS[$i]}"
done
record INFO "seed: baseline subscriptions to communities 3/4" "spread users 5..$N_USERS"

################################################################################
echo ""
echo "=== Checkpoint 2: atomic subscriber_count under concurrency ==="
################################################################################
# 15 of the 18 seeded users subscribe to community 1 at the same time (creator of community 1,
# seeduser1, is already a member from POST /r's auto-join).
BURST_COMM="${COMM_NAMES[1]}"
for i in $(seq 2 16); do
  curl -s -o /dev/null -X POST "$BASE/r/$BURST_COMM/subscribe" -H "Authorization: Bearer ${TOKENS[$i]}" &
done
wait
SUB_COUNT=$(psql_c "SELECT subscriber_count FROM communities WHERE name='${BURST_COMM}';")
MEMBER_COUNT=$(psql_c "SELECT count(*) FROM memberships WHERE community_id=(SELECT id FROM communities WHERE name='${BURST_COMM}');")
if [ "$SUB_COUNT" = "16" ] && [ "$MEMBER_COUNT" = "16" ] && [ "$SUB_COUNT" = "$MEMBER_COUNT" ]; then
  record PASS "checkpoint 2: subscriber_count matches memberships under 15 concurrent subscribes" "subscriber_count=$SUB_COUNT memberships=$MEMBER_COUNT (1 creator + 15 concurrent)"
else
  record FAIL "checkpoint 2: subscriber_count matches memberships under 15 concurrent subscribes" "subscriber_count=$SUB_COUNT memberships=$MEMBER_COUNT (expected both=16)"
fi

################################################################################
echo ""
echo "=== Checkpoint 3: idempotent submit + keyset pagination (zero overlap) ==="
################################################################################
PAGI_COMM="${COMM_NAMES[2]}"
FIRST_POST_ID=""
FIRST_POST_POSTER=""
for i in $(seq 1 31); do
  poster=${TOKENS[$(( (i % N_USERS) + 1 ))]}
  req POST "/r/$PAGI_COMM/submit" "{\"kind\":\"text\",\"title\":\"seed post $i\",\"body\":\"seed body $i\"}" \
    -H "Authorization: Bearer $poster" -H "Idempotency-Key: seed-${RUN}-p${i}"
  id=$(echo "$HTTP_BODY" | jq -r .id 2>/dev/null)
  if [ "$i" = "1" ]; then FIRST_POST_ID="$id"; FIRST_POST_POSTER="$poster"; fi
done
record INFO "seed: 31 posts submitted to $PAGI_COMM" "for keyset-pagination + comment-thread tests"

# duplicate Idempotency-Key resubmission (same key, same body, same poster as post #1 — the
# idempotency key is scoped per-user: "idempotency:" + authorId + ":" + idempotencyKey, so the
# retry must come from the identical user for the dedup to apply)
req POST "/r/$PAGI_COMM/submit" "{\"kind\":\"text\",\"title\":\"seed post 1\",\"body\":\"seed body 1\"}" \
  -H "Authorization: Bearer $FIRST_POST_POSTER" -H "Idempotency-Key: seed-${RUN}-p1"
DUP_ID=$(echo "$HTTP_BODY" | jq -r .id 2>/dev/null)
if [ "$DUP_ID" = "$FIRST_POST_ID" ]; then
  record PASS "checkpoint 3a: duplicate Idempotency-Key returns same post id" "first=$FIRST_POST_ID dup=$DUP_ID"
else
  record FAIL "checkpoint 3a: duplicate Idempotency-Key returns same post id" "first=$FIRST_POST_ID dup=$DUP_ID"
fi

req GET "/r/$PAGI_COMM/new" ""
PAGE1_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
PAGE1_AFTER=$(echo "$HTTP_BODY" | jq -r '.data.after')
PAGE1_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id' | sort)
if [ "$PAGE1_COUNT" = "25" ] && [ -n "$PAGE1_AFTER" ] && [ "$PAGE1_AFTER" != "null" ]; then
  record PASS "checkpoint 3b: page 1 of /new returns exactly 25 (PAGE_SIZE) with an after-cursor" "children=$PAGE1_COUNT after=${PAGE1_AFTER:0:20}..."
else
  record FAIL "checkpoint 3b: page 1 of /new returns exactly 25 (PAGE_SIZE) with an after-cursor" "children=$PAGE1_COUNT after=$PAGE1_AFTER"
fi

req GET "/r/$PAGI_COMM/new?after=$PAGE1_AFTER" ""
PAGE2_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
PAGE2_IDS=$(echo "$HTTP_BODY" | jq -r '.data.children[].data.id' | sort)
OVERLAP=$(comm -12 <(echo "$PAGE1_IDS") <(echo "$PAGE2_IDS") | wc -l | tr -d ' ')
if [ "$OVERLAP" = "0" ] && [ "$PAGE2_COUNT" -ge 1 ]; then
  record PASS "checkpoint 3c: page 2 has zero id overlap with page 1" "page2 children=$PAGE2_COUNT overlap=$OVERLAP"
else
  record FAIL "checkpoint 3c: page 2 has zero id overlap with page 1" "page2 children=$PAGE2_COUNT overlap=$OVERLAP"
fi
record INFO "checkpoint 3d: findNewPage never uses OFFSET" "verified by source (PostRepository.findNewPage, §6 build log) — keyset WHERE clause only"

################################################################################
echo ""
echo "=== Checkpoint 4: comment depth-10 cap + ltree path shape ==="
################################################################################
PARENT=""
DEPTH_OK=true
for d in $(seq 0 10); do
  if [ "$d" = "0" ]; then
    req POST /api/comment "{\"postId\":\"$FIRST_POST_ID\",\"body\":\"seed depth $d\"}" -H "Authorization: Bearer ${TOKENS[1]}"
  else
    req POST /api/comment "{\"postId\":\"$FIRST_POST_ID\",\"parentId\":\"$PARENT\",\"body\":\"seed depth $d\"}" -H "Authorization: Bearer ${TOKENS[1]}"
  fi
  NEWID=$(echo "$HTTP_BODY" | jq -r .id 2>/dev/null)
  ACTUAL_DEPTH=$(echo "$HTTP_BODY" | jq -r .depth 2>/dev/null)
  if [ "$HTTP_STATUS" != "200" ] || [ "$ACTUAL_DEPTH" != "$d" ]; then
    DEPTH_OK=false
    record FAIL "checkpoint 4a: reply chain depth $d created with correct depth" "HTTP $HTTP_STATUS depth field=$ACTUAL_DEPTH body=$HTTP_BODY"
  fi
  PARENT="$NEWID"
done
if [ "$DEPTH_OK" = "true" ]; then
  record PASS "checkpoint 4a: reply chain built to depth 10, each depth field correct" "11 comments created, depths 0..10 all matched"
fi

req POST /api/comment "{\"postId\":\"$FIRST_POST_ID\",\"parentId\":\"$PARENT\",\"body\":\"too deep\"}" -H "Authorization: Bearer ${TOKENS[1]}"
DEPTH11_MSG=$(echo "$HTTP_BODY" | jq -r .message 2>/dev/null)
expect_status "checkpoint 4b: depth-11 reply rejected" "400" "$HTTP_STATUS" "message=\"$DEPTH11_MSG\""

# NOTE: '||' is ltree's own path-concatenation operator on an ltree operand, not string
# concatenation — casting path to text first avoids Postgres trying (and failing) to parse the
# literal '|' as an ltree label.
PATH_ROW=$(psql_c "SELECT depth, path::text FROM comments WHERE post_id='$FIRST_POST_ID' AND depth=10;")
DOT_COUNT=$(echo "$PATH_ROW" | awk -F'|' '{print $2}' | tr -cd '.' | wc -c | tr -d ' ')
if [ "$DOT_COUNT" = "10" ]; then
  record PASS "checkpoint 4c: depth-10 comment's ltree path has 11 dot-joined segments" "path=${PATH_ROW:0:60}... (10 dots = 11 segments)"
else
  record FAIL "checkpoint 4c: depth-10 comment's ltree path has 11 dot-joined segments" "dots=$DOT_COUNT path=$PATH_ROW"
fi

# two extra top-level comments on the same post, to make the top-level-only filter meaningful
req POST /api/comment "{\"postId\":\"$FIRST_POST_ID\",\"body\":\"seed extra top-level A\"}" -H "Authorization: Bearer ${TOKENS[2]}"
req POST /api/comment "{\"postId\":\"$FIRST_POST_ID\",\"body\":\"seed extra top-level B\"}" -H "Authorization: Bearer ${TOKENS[3]}"

req GET "/r/$PAGI_COMM/comments/$FIRST_POST_ID" ""
TOPLEVEL_COUNT=$(echo "$HTTP_BODY" | jq '.comments.data.children | length')
if [ "$TOPLEVEL_COUNT" = "3" ]; then
  record PASS "checkpoint 4d: GET comments returns only top-level (3), excludes the 10 nested replies" "returned=$TOPLEVEL_COUNT"
else
  record FAIL "checkpoint 4d: GET comments returns only top-level (3), excludes the 10 nested replies" "returned=$TOPLEVEL_COUNT"
fi

################################################################################
echo ""
echo "=== Checkpoint 1: auth round-trip (fresh, on a newly seeded user) ==="
################################################################################
req POST /api/v1/access_token "{\"username\":\"${USERNAMES[1]}\",\"password\":\"$PASSWORD\"}"
LOGIN_ACCESS=$(echo "$HTTP_BODY" | jq -r .accessToken)
SET_COOKIE=$(curl -s -D - -o /dev/null -X POST "$BASE/api/v1/access_token" -H 'Content-Type: application/json' \
  -d "{\"username\":\"${USERNAMES[1]}\",\"password\":\"$PASSWORD\"}" | grep -i '^set-cookie' | sed -E 's/.*refresh_token=([^;]+);.*/\1/')
expect_status "checkpoint 1a: login returns 200 with accessToken" "200" "$HTTP_STATUS" "accessToken present: $([ -n "$LOGIN_ACCESS" ] && echo yes || echo no)"

req GET /api/v1/me "" -H "Authorization: Bearer $LOGIN_ACCESS"
ME_USERNAME=$(echo "$HTTP_BODY" | jq -r .username 2>/dev/null)
expect_status "checkpoint 1b: /me with valid token" "200" "$HTTP_STATUS" "username=$ME_USERNAME"

req GET /api/v1/me ""
expect_status "checkpoint 1c: /me with no token" "401" "$HTTP_STATUS" "body=$HTTP_BODY"

req GET /api/v1/me "" -H "Authorization: Bearer garbage.garbage.garbage"
expect_status "checkpoint 1d: /me with garbage token" "401" "$HTTP_STATUS" "body=$HTTP_BODY"

req GET "/user/${USERNAMES[1]}/about" ""
expect_status "checkpoint 1e: /user/{username}/about with no token" "200" "$HTTP_STATUS" "body=$HTTP_BODY"

req POST /api/v1/access_token/refresh "" -H "Cookie: refresh_token=$SET_COOKIE"
NEW_COOKIE=$(curl -s -D - -o /dev/null -X POST "$BASE/api/v1/access_token/refresh" -H "Cookie: refresh_token=$SET_COOKIE" \
  | grep -i '^set-cookie' | sed -E 's/.*refresh_token=([^;]+);.*/\1/')
expect_status "checkpoint 1f: refresh with valid cookie rotates" "200" "$HTTP_STATUS" "new cookie issued: $([ -n "$NEW_COOKIE" ] && echo yes || echo no)"

# reuse the now-revoked original cookie -> theft signal, should 401 and kill the whole family
req POST /api/v1/access_token/refresh "" -H "Cookie: refresh_token=$SET_COOKIE"
REUSE_MSG=$(echo "$HTTP_BODY" | jq -r .message 2>/dev/null)
expect_status "checkpoint 1g: reusing an already-rotated refresh token is rejected" "401" "$HTTP_STATUS" "message=\"$REUSE_MSG\""

# the legitimately-latest cookie should now ALSO be dead, since reuse detection revokes the whole family
req POST /api/v1/access_token/refresh "" -H "Cookie: refresh_token=$NEW_COOKIE"
FAMILY_KILL_MSG=$(echo "$HTTP_BODY" | jq -r .message 2>/dev/null)
expect_status "checkpoint 1h: token-theft detection revokes the entire family (latest cookie also dead)" "401" "$HTTP_STATUS" "message=\"$FAMILY_KILL_MSG\""

################################################################################
echo ""
echo "=== citext case-insensitive uniqueness (data-shape check) ==="
################################################################################
DUP_EMAIL_UPPER=$(echo "seed${RUN}u1@example.com" | tr '[:lower:]' '[:upper:]')
req POST /api/v1/register "{\"username\":\"seed${RUN}dup\",\"email\":\"$DUP_EMAIL_UPPER\",\"password\":\"$PASSWORD\"}"
if [ "$HTTP_STATUS" = "409" ]; then
  record PASS "citext: duplicate email differing only in case is rejected" "tried email=$DUP_EMAIL_UPPER (existing: seed${RUN}u1@example.com)"
else
  record FAIL "citext: duplicate email differing only in case is rejected" "HTTP $HTTP_STATUS (expected 409) — ROOT CAUSE (confirmed via psql PREPARE): UserRepository.existsByEmail binds its parameter as a typed varchar (Hibernate/pgjdbc's setString default); Postgres then resolves \`citext_col = varchar_param\` by casting the citext COLUMN down to text, which is case-SENSITIVE, instead of casting the parameter up to citext. A bare SQL literal (untyped/unknown) or an explicitly-untyped bound parameter both compare case-insensitively as expected; a concretely-typed varchar parameter does not. The DB's UNIQUE constraint still catches this at insert time (case-insensitively — citext's index comparison isn't affected), so this is an application pre-check gap, not a data-integrity gap. Fix candidate: add stringtype=unspecified to the JDBC URL (pgjdbc's documented mechanism for exactly this class of problem — verified directly with psql to restore case-insensitive matching) rather than a per-query cast."
fi

UPPER_USERNAME=$(echo "${USERNAMES[1]}" | tr '[:lower:]' '[:upper:]')
req GET "/user/$UPPER_USERNAME/about" ""
LOOKUP_USERNAME=$(echo "$HTTP_BODY" | jq -r .username 2>/dev/null)
if [ "$HTTP_STATUS" = "200" ] && [ "$LOOKUP_USERNAME" = "${USERNAMES[1]}" ]; then
  record PASS "citext: case-insensitive username lookup finds the same user" "queried=$UPPER_USERNAME found=$LOOKUP_USERNAME"
else
  record FAIL "citext: case-insensitive username lookup finds the same user" "HTTP $HTTP_STATUS queried=$UPPER_USERNAME found=$LOOKUP_USERNAME — same root cause as the email check above: UserRepository.findByUsername's bound parameter is typed varchar, not unspecified, so the lookup is effectively case-sensitive despite the column being citext"
fi

# duplicate USERNAME differing only in case, with a fresh email — AuthService only pre-checks
# email uniqueness (existsByEmail), not username, before insert. Real behavior observed, not assumed.
DUP_USERNAME_UPPER="$(echo "${USERNAMES[1]}" | cut -c1 | tr '[:lower:]' '[:upper:]')$(echo "${USERNAMES[1]}" | cut -c2-)"
req POST /api/v1/register "{\"username\":\"$DUP_USERNAME_UPPER\",\"email\":\"seed${RUN}freshmail@example.com\",\"password\":\"$PASSWORD\"}"
if [ "$HTTP_STATUS" = "409" ]; then
  record PASS "citext: duplicate username differing only in case is rejected cleanly" "HTTP 409, body=$HTTP_BODY"
else
  record FAIL "citext: duplicate username differing only in case is rejected cleanly" "HTTP $HTTP_STATUS (expected 409) body=$HTTP_BODY — AuthService.register() only pre-checks existsByEmail, not username; the DB's citext UNIQUE constraint still blocks the insert, but as a raw DataIntegrityViolationException GlobalExceptionHandler does not map, surfacing as an unhandled 500 instead of a clean 409"
fi

################################################################################
echo ""
echo "=== Happy-path / failure-path matrix for remaining endpoints ==="
################################################################################

# POST /api/v1/register — failure: duplicate email
req POST /api/v1/register "{\"username\":\"seed${RUN}dup2\",\"email\":\"seed${RUN}u1@example.com\",\"password\":\"$PASSWORD\"}"
expect_status "POST /api/v1/register — duplicate email (exact case)" "409" "$HTTP_STATUS" "body=$HTTP_BODY"

# POST /api/v1/access_token — failure: wrong password
req POST /api/v1/access_token "{\"username\":\"${USERNAMES[1]}\",\"password\":\"wrong-password-entirely\"}"
expect_status "POST /api/v1/access_token — wrong password" "401" "$HTTP_STATUS" "body=$HTTP_BODY"

# GET /user/{username}/about — failure: nonexistent user
req GET "/user/seed${RUN}doesnotexist/about" ""
expect_status "GET /user/{username}/about — nonexistent user" "404" "$HTTP_STATUS" "body=$HTTP_BODY"

# POST /r — failure: duplicate community name (real behavior observed, not assumed)
req POST /r "{\"name\":\"${COMM_NAMES[1]}\",\"description\":\"dup\"}" -H "Authorization: Bearer ${TOKENS[2]}"
if [ "$HTTP_STATUS" = "409" ]; then
  record PASS "POST /r — duplicate community name rejected cleanly" "HTTP 409"
else
  record FAIL "POST /r — duplicate community name rejected cleanly" "HTTP $HTTP_STATUS (expected 409) body=$HTTP_BODY — CommunityService.create() never checks for an existing name before insert; the DB's citext UNIQUE constraint blocks it, but as an unmapped DataIntegrityViolationException"
fi

# POST /r/{name}/subscribe — failure: nonexistent community
req POST "/r/seed${RUN}doesnotexist/subscribe" "" -H "Authorization: Bearer ${TOKENS[2]}"
expect_status "POST /r/{name}/subscribe — nonexistent community" "404" "$HTTP_STATUS" "body=$HTTP_BODY"

# DELETE /r/{name}/subscribe — happy path (decrements count) + documented no-op behavior
req DELETE "/r/${COMM_NAMES[3]}/subscribe" "" -H "Authorization: Bearer ${TOKENS[6]}"
BEFORE_UNSUB=$(psql_c "SELECT subscriber_count FROM communities WHERE name='${COMM_NAMES[3]}';")
expect_status "DELETE /r/{name}/subscribe — happy path" "200" "$HTTP_STATUS" "subscriber_count now $BEFORE_UNSUB"
req DELETE "/r/${COMM_NAMES[3]}/subscribe" "" -H "Authorization: Bearer ${TOKENS[6]}"
record INFO "DELETE /r/{name}/subscribe — unsubscribing again (never-subscribed case)" "HTTP $HTTP_STATUS — designed as an idempotent no-op (CommunityService.leave), not an error"

# POST /r/{name}/submit — failure: missing Idempotency-Key header
req POST "/r/${COMM_NAMES[3]}/submit" "{\"kind\":\"text\",\"title\":\"no key\",\"body\":\"x\"}" -H "Authorization: Bearer ${TOKENS[6]}"
expect_status "POST /r/{name}/submit — missing Idempotency-Key header" "400" "$HTTP_STATUS" "body=$HTTP_BODY"

# POST /r/{name}/submit — failure: invalid kind
req POST "/r/${COMM_NAMES[3]}/submit" "{\"kind\":\"bogus\",\"title\":\"bad kind\",\"body\":\"x\"}" -H "Authorization: Bearer ${TOKENS[6]}" -H "Idempotency-Key: seed-${RUN}-badkind"
expect_status "POST /r/{name}/submit — invalid kind value" "400" "$HTTP_STATUS" "body=$HTTP_BODY"

# GET /r/{name}/new — failure: nonexistent community
req GET "/r/seed${RUN}doesnotexist/new" ""
expect_status "GET /r/{name}/new — nonexistent community" "404" "$HTTP_STATUS" "body=$HTTP_BODY"

# POST /api/comment — failure: nonexistent parentId
req POST /api/comment "{\"postId\":\"$FIRST_POST_ID\",\"parentId\":\"00000000-0000-0000-0000-000000000000\",\"body\":\"orphan reply\"}" -H "Authorization: Bearer ${TOKENS[1]}"
expect_status "POST /api/comment — nonexistent parentId" "404" "$HTTP_STATUS" "body=$HTTP_BODY"

# POST /api/comment — informational: nonexistent postId is NOT validated (no FK, by design)
req POST /api/comment "{\"postId\":\"00000000-0000-0000-0000-000000000000\",\"body\":\"orphan comment\"}" -H "Authorization: Bearer ${TOKENS[1]}"
record INFO "POST /api/comment — nonexistent postId" "HTTP $HTTP_STATUS — comments.post_id has no FK constraint by design (Data model, Resolved design decisions); CommentService.reply() does not check post existence either, so this currently succeeds and creates an orphaned comment row rather than 404ing"

# GET /r/{name}/comments/{postId} — failure: nonexistent postId
req GET "/r/${COMM_NAMES[3]}/comments/00000000-0000-0000-0000-000000000000" ""
expect_status "GET /r/{name}/comments/{postId} — nonexistent postId" "404" "$HTTP_STATUS" "body=$HTTP_BODY"

################################################################################
echo ""
echo "=== Final table counts (after seeding) ==="
################################################################################
AFTER=$(psql_c "SELECT 'users',count(*) FROM users UNION ALL SELECT 'communities',count(*) FROM communities UNION ALL SELECT 'memberships',count(*) FROM memberships UNION ALL SELECT 'posts',count(*) FROM posts UNION ALL SELECT 'comments',count(*) FROM comments;")
echo "$AFTER"

echo ""
echo "=== SUMMARY ==="
echo "PASS: $PASS_COUNT   FAIL: $FAIL_COUNT   INFO: $INFO_COUNT"
echo ""
echo "--- Full results ---"
for r in "${RESULTS[@]}"; do
  echo "$r"
done

if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
exit 0
