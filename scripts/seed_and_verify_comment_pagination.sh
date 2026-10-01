#!/usr/bin/env bash
# Seeds a post with more top-level comments than TOP_LEVEL_PAGE_SIZE and a single root with more direct
# replies than REPLY_PAGE_SIZE, then re-verifies backend feature 8 (real top-level comment pagination +
# GET /api/morechildren) end to end: a full page of top-level comments carries a real "after" cursor for
# sort=new (descending Cursor), sort=old (the new ascending-cursor code path, Cursor.FIRST_PAGE_ASC), and
# sort=best (RankCursor) — page 2 continues correctly with no duplicates/gaps across all three; a root
# whose direct children exceed REPLY_PAGE_SIZE gets its reply subtree truncated in the main page response,
# detectable via childCount > replies.length; GET /api/morechildren fully and correctly paginates that
# root's remaining children across its own pages with no duplicates/gaps; morechildren 404s for a
# mismatched parentId/postId pair and 403s against a private community's post for a non-member. Prints
# PASS/FAIL with the real observed value for every check. Data is left in the dev database afterward.
#
# Prereqs: docker compose stack up, app running on $BASE.

set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
RUN=$(date +%s | tail -c 6)
PASSWORD="Sup3rSecret!1"

PASS_COUNT=0
FAIL_COUNT=0
declare -a RESULTS=()

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

# macOS ships bash 3.2 (no mapfile/readarray, added in bash 4.0) — a plain while-read loop into an array
# is the portable equivalent.
read_lines_into() {
  local __arrname="$1" __jqfilter="$2" __line
  eval "$__arrname=()"
  while IFS= read -r __line; do
    eval "$__arrname+=(\"\$__line\")"
  done < <(echo "$HTTP_BODY" | jq -r "$__jqfilter")
}

register() {
  local uname="cp${RUN}$1"
  req POST /api/v1/register "{\"username\":\"$uname\",\"email\":\"$uname@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}

submit() {
  local token="$1" comm="$2" title="$3" key="$4"
  req POST "/r/$comm/submit" "{\"kind\":\"text\",\"title\":\"$title\",\"body\":\"body\"}" \
    -H "Authorization: Bearer $token" -H "Idempotency-Key: $key"
}

comment() {
  local token="$1" postId="$2" body="$3" parentId="${4:-}"
  if [ -z "$parentId" ]; then
    req POST /api/comment "{\"postId\":\"$postId\",\"body\":\"$body\"}" -H "Authorization: Bearer $token"
  else
    req POST /api/comment "{\"postId\":\"$postId\",\"parentId\":\"$parentId\",\"body\":\"$body\"}" -H "Authorization: Bearer $token"
  fi
}

# unique_count_matches <expected_count> <space-separated ids...> -> prints "true"/"false" and the actual count
assert_exact_set() {
  local desc="$1" expected_count="$2"; shift 2
  local actual_unique
  actual_unique=$(printf '%s\n' "$@" | sort -u | wc -l | tr -d ' ')
  local actual_count=$#
  if [ "$actual_unique" = "$expected_count" ] && [ "$actual_count" = "$expected_count" ]; then
    record PASS "$desc" "expected $expected_count unique ids, got $actual_unique unique of $actual_count total"
  else
    record FAIL "$desc" "expected $expected_count unique ids, got $actual_unique unique of $actual_count total"
  fi
}

################################################################################
echo "=== Phase A: seed author, public community, and a post with 55 top-level comments ==="
################################################################################

AUTHOR=$(register author)
OUTSIDER=$(register outsider)
COMM="cppub${RUN}"
req POST /r "{\"name\":\"$COMM\",\"description\":\"pagination seed\",\"type\":\"public\"}" -H "Authorization: Bearer $AUTHOR"
expect_status "create the public community" "200" "$HTTP_STATUS" "-"

submit "$AUTHOR" "$COMM" "comment pagination test post" "cp-toplevel-${RUN}"
expect_status "create the top-level-comments post" "200" "$HTTP_STATUS" "-"
TOPLEVEL_POST_ID=$(echo "$HTTP_BODY" | jq -r .id)

TOPLEVEL_IDS=()
for i in $(seq 1 55); do
  comment "$AUTHOR" "$TOPLEVEL_POST_ID" "toplevel comment $i"
  TOPLEVEL_IDS+=("$(echo "$HTTP_BODY" | jq -r .id)")
done
expect_eq "seeded exactly 55 top-level comments" "55" "${#TOPLEVEL_IDS[@]}"

################################################################################
echo "=== Phase B: real top-level pagination across 3 cursor code paths (new/old/best) ==="
################################################################################

for sort in new old best; do
  req GET "/r/$COMM/comments/$TOPLEVEL_POST_ID?sort=$sort" ""
  expect_status "sort=$sort page 1 returns 200" "200" "$HTTP_STATUS" "-"
  PAGE1_COUNT=$(echo "$HTTP_BODY" | jq '.comments.data.children | length')
  expect_eq "sort=$sort page 1 has exactly 50 comments (TOP_LEVEL_PAGE_SIZE)" "50" "$PAGE1_COUNT"
  PAGE1_AFTER=$(echo "$HTTP_BODY" | jq -r '.comments.data.after')
  if [ "$PAGE1_AFTER" != "null" ] && [ -n "$PAGE1_AFTER" ]; then
    record PASS "sort=$sort page 1 carries a real 'after' cursor (full page)" "after=$PAGE1_AFTER"
  else
    record FAIL "sort=$sort page 1 carries a real 'after' cursor (full page)" "after=$PAGE1_AFTER"
  fi
  read_lines_into PAGE1_IDS '.comments.data.children[].data.id'

  req GET "/r/$COMM/comments/$TOPLEVEL_POST_ID?sort=$sort&after=$PAGE1_AFTER" ""
  expect_status "sort=$sort page 2 returns 200" "200" "$HTTP_STATUS" "-"
  PAGE2_COUNT=$(echo "$HTTP_BODY" | jq '.comments.data.children | length')
  expect_eq "sort=$sort page 2 has the remaining 5 comments" "5" "$PAGE2_COUNT"
  PAGE2_AFTER=$(echo "$HTTP_BODY" | jq -r '.comments.data.after')
  expect_eq "sort=$sort page 2 is the end (after is null)" "null" "$PAGE2_AFTER"
  read_lines_into PAGE2_IDS '.comments.data.children[].data.id'

  assert_exact_set "sort=$sort pages 1+2 together cover all 55 comments with no duplicates/gaps" "55" "${PAGE1_IDS[@]}" "${PAGE2_IDS[@]}"
done

################################################################################
echo "=== Phase C: a root with 60 direct replies gets its subtree bounded (REPLY_PAGE_SIZE=50) ==="
################################################################################

submit "$AUTHOR" "$COMM" "comment pagination big thread post" "cp-bigthread-${RUN}"
expect_status "create the big-thread post" "200" "$HTTP_STATUS" "-"
BIGTHREAD_POST_ID=$(echo "$HTTP_BODY" | jq -r .id)

comment "$AUTHOR" "$BIGTHREAD_POST_ID" "the root of a big thread"
ROOT_ID=$(echo "$HTTP_BODY" | jq -r .id)

REPLY_IDS=()
for i in $(seq 1 60); do
  comment "$AUTHOR" "$BIGTHREAD_POST_ID" "direct reply $i" "$ROOT_ID"
  REPLY_IDS+=("$(echo "$HTTP_BODY" | jq -r .id)")
done
expect_eq "seeded exactly 60 direct replies on the root" "60" "${#REPLY_IDS[@]}"

req GET "/r/$COMM/comments/$BIGTHREAD_POST_ID?sort=new" ""
expect_status "GET the big-thread post's comments" "200" "$HTTP_STATUS" "-"
ROOT_COUNT=$(echo "$HTTP_BODY" | jq '.comments.data.children | length')
expect_eq "exactly 1 top-level comment on the big-thread post" "1" "$ROOT_COUNT"
ROOT_CHILD_COUNT=$(echo "$HTTP_BODY" | jq -r '.comments.data.children[0].data.childCount')
expect_eq "the root's childCount reflects all 60 real direct children" "60" "$ROOT_CHILD_COUNT"
ROOT_REPLIES_INCLUDED=$(echo "$HTTP_BODY" | jq '.comments.data.children[0].data.replies | length')
expect_eq "the root's included replies are bounded at REPLY_PAGE_SIZE (50), not all 60" "50" "$ROOT_REPLIES_INCLUDED"
if [ "$ROOT_CHILD_COUNT" -gt "$ROOT_REPLIES_INCLUDED" ]; then
  record PASS "childCount > replies.length correctly signals truncation on this node" "childCount=$ROOT_CHILD_COUNT replies=$ROOT_REPLIES_INCLUDED"
else
  record FAIL "childCount > replies.length correctly signals truncation on this node" "childCount=$ROOT_CHILD_COUNT replies=$ROOT_REPLIES_INCLUDED"
fi
ROOT_REPLIES_AFTER=$(echo "$HTTP_BODY" | jq -r '.comments.data.children[0].data.repliesAfter')
if [ "$ROOT_REPLIES_AFTER" != "null" ] && [ -n "$ROOT_REPLIES_AFTER" ]; then
  record PASS "the truncated root carries a real repliesAfter cursor" "repliesAfter=$ROOT_REPLIES_AFTER"
else
  record FAIL "the truncated root carries a real repliesAfter cursor" "repliesAfter=$ROOT_REPLIES_AFTER"
fi
read_lines_into ROOT_SHOWN_IDS '.comments.data.children[0].data.replies[].id'

################################################################################
echo "=== Phase D: repliesAfter continues the truncated root with zero overlap (the real frontend flow) ==="
################################################################################

req GET "/api/morechildren?postId=$BIGTHREAD_POST_ID&parentId=$ROOT_ID&sort=new&after=$ROOT_REPLIES_AFTER" ""
expect_status "morechildren using the real repliesAfter cursor returns 200" "200" "$HTTP_STATUS" "-"
CONT_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
expect_eq "it returns exactly the remaining 10 replies, not a re-shown page 1" "10" "$CONT_COUNT"
CONT_AFTER=$(echo "$HTTP_BODY" | jq -r '.data.after')
expect_eq "it correctly reports the end (after is null)" "null" "$CONT_AFTER"
read_lines_into CONT_IDS '.data.children[].data.id'
assert_exact_set "the 50 already-shown + these 10 continued replies cover all 60 with zero overlap" "60" "${ROOT_SHOWN_IDS[@]}" "${CONT_IDS[@]}"

################################################################################
echo "=== Phase E: GET /api/morechildren also fully and correctly paginates the root's 60 children on its own (no after) ==="
################################################################################

req GET "/api/morechildren?postId=$BIGTHREAD_POST_ID&parentId=$ROOT_ID&sort=new" ""
expect_status "morechildren page 1 returns 200" "200" "$HTTP_STATUS" "-"
MC_PAGE1_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
expect_eq "morechildren page 1 has exactly 50 children (REPLY_PAGE_SIZE)" "50" "$MC_PAGE1_COUNT"
MC_PAGE1_AFTER=$(echo "$HTTP_BODY" | jq -r '.data.after')
if [ "$MC_PAGE1_AFTER" != "null" ] && [ -n "$MC_PAGE1_AFTER" ]; then
  record PASS "morechildren page 1 carries a real 'after' cursor (full page)" "after=$MC_PAGE1_AFTER"
else
  record FAIL "morechildren page 1 carries a real 'after' cursor (full page)" "after=$MC_PAGE1_AFTER"
fi
read_lines_into MC_PAGE1_IDS '.data.children[].data.id'
MC_PAGE1_FIRST_REPLIES=$(echo "$HTTP_BODY" | jq '.data.children[0].data.replies | length')
expect_eq "morechildren's returned children carry no nested replies of their own (one level at a time)" "0" "$MC_PAGE1_FIRST_REPLIES"

req GET "/api/morechildren?postId=$BIGTHREAD_POST_ID&parentId=$ROOT_ID&sort=new&after=$MC_PAGE1_AFTER" ""
expect_status "morechildren page 2 returns 200" "200" "$HTTP_STATUS" "-"
MC_PAGE2_COUNT=$(echo "$HTTP_BODY" | jq '.data.children | length')
expect_eq "morechildren page 2 has the remaining 10 children" "10" "$MC_PAGE2_COUNT"
MC_PAGE2_AFTER=$(echo "$HTTP_BODY" | jq -r '.data.after')
expect_eq "morechildren page 2 is the end (after is null)" "null" "$MC_PAGE2_AFTER"
read_lines_into MC_PAGE2_IDS '.data.children[].data.id'

assert_exact_set "morechildren pages 1+2 together cover all 60 real direct-reply ids with no duplicates/gaps" "60" "${MC_PAGE1_IDS[@]}" "${MC_PAGE2_IDS[@]}"

################################################################################
echo "=== Phase F: morechildren validation and access control ==="
################################################################################

submit "$AUTHOR" "$COMM" "a second, unrelated post" "cp-other-${RUN}"
OTHER_POST_ID=$(echo "$HTTP_BODY" | jq -r .id)

req GET "/api/morechildren?postId=$OTHER_POST_ID&parentId=$ROOT_ID&sort=new" ""
expect_status "morechildren 404s when parentId doesn't belong to postId" "404" "$HTTP_STATUS" "-"

PRIVCOMM="cppriv${RUN}"
req POST /r "{\"name\":\"$PRIVCOMM\",\"description\":\"private seed\",\"type\":\"private\"}" -H "Authorization: Bearer $AUTHOR"
expect_status "create the private community" "200" "$HTTP_STATUS" "-"
submit "$AUTHOR" "$PRIVCOMM" "private post for morechildren access check" "cp-privpost-${RUN}"
PRIV_POST_ID=$(echo "$HTTP_BODY" | jq -r .id)
comment "$AUTHOR" "$PRIV_POST_ID" "private root"
PRIV_ROOT_ID=$(echo "$HTTP_BODY" | jq -r .id)

req GET "/api/morechildren?postId=$PRIV_POST_ID&parentId=$PRIV_ROOT_ID&sort=new" "" -H "Authorization: Bearer $OUTSIDER"
expect_status "morechildren 403s a non-member against a private community's post" "403" "$HTTP_STATUS" "-"

################################################################################
echo
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
