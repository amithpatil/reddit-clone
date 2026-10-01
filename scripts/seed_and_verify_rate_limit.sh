#!/usr/bin/env bash
# Re-verifies backend feature 9 (rate limiting) end to end against the real shipped defaults
# (application.yml: 5 logins/minute, 3 registrations/hour per IP, both refillIntervally so a window only
# resets as a whole, not a trickle): exceeding either bucket's capacity returns 429 with the standard
# {timestamp,status,error,message} body shape instead of the normal 401/200; the two buckets are
# independent (exhausting one never blocks the other); deleting a bucket's Redis key directly (simulating
# its window having elapsed, without a real-time wait — this suite's own login/register calls above are
# what already exhausted it) makes the very next request succeed again, proving the full reset path works,
# not just the block. Prints PASS/FAIL with the real observed value for every check. Data is left in the
# dev database afterward.
#
# Must run against an app instance started with the real shipped rate-limit config (no
# RATE_LIMIT_*_CAPACITY env overrides) — the regression suite's own heavy registration/login volume needs
# those loosened for its own run; restart the app with no overrides before running this script alone.
#
# Prereqs: docker compose stack up, app running on $BASE with real (unloosened) rate-limit config.

set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
PASSWORD="Sup3rSecret!1"
RUN=$(date +%s | tail -c 6)

# Must match application.yml's real shipped defaults (RATE_LIMIT_LOGIN_CAPACITY / _REGISTER_CAPACITY etc.)
# — overridable here only so this script stays correct if those defaults are ever deliberately changed.
LOGIN_CAPACITY="${RATE_LIMIT_LOGIN_CAPACITY:-5}"
REGISTER_CAPACITY="${RATE_LIMIT_REGISTER_CAPACITY:-3}"

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

# Discovers the real bucket key dynamically rather than hardcoding how getRemoteAddr() stringifies
# localhost (confirmed to be the IPv6 loopback form "0:0:0:0:0:0:0:1" in this dev environment, but this
# shouldn't be assumed elsewhere — e.g. a different docker network mode could report a plain IPv4 address).
flush_bucket() {
  local bucketName="$1"
  local keys
  keys=$(docker compose exec -T redis redis-cli KEYS "ratelimit:${bucketName}:*" | tr -d '\r')
  if [ -n "$keys" ]; then
    echo "$keys" | while IFS= read -r k; do
      [ -n "$k" ] && docker compose exec -T redis redis-cli DEL "$k" > /dev/null
    done
  fi
}

################################################################################
echo "=== Phase A: clean slate — flush any pre-existing login/register buckets for this test runner's IP ==="
################################################################################

flush_bucket login
flush_bucket register

################################################################################
echo "=== Phase B: seed one throwaway user to attempt wrong-password logins against ==="
################################################################################

TARGET_USER="rl${RUN}target"
req POST /api/v1/register "{\"username\":\"$TARGET_USER\",\"email\":\"${TARGET_USER}@example.com\",\"password\":\"$PASSWORD\"}"
expect_status "seed the login-test target account" "200" "$HTTP_STATUS" "-"

# That registration just consumed one token from the register bucket — flush it again so Phase D's
# exhaustion count starts from a known-clean REGISTER_CAPACITY, not REGISTER_CAPACITY-1.
flush_bucket register

################################################################################
echo "=== Phase C: exceeding login's capacity (${LOGIN_CAPACITY}/min) returns 429, not 401 ==="
################################################################################

for i in $(seq 1 "$LOGIN_CAPACITY"); do
  req POST /api/v1/access_token "{\"username\":\"$TARGET_USER\",\"password\":\"wrong-password\"}"
  expect_status "login attempt $i/$LOGIN_CAPACITY is within capacity (401, wrong password)" "401" "$HTTP_STATUS" "-"
done

req POST /api/v1/access_token "{\"username\":\"$TARGET_USER\",\"password\":\"wrong-password\"}"
expect_status "login attempt $((LOGIN_CAPACITY+1)) exceeds capacity (429)" "429" "$HTTP_STATUS" "-"
RL_ERROR=$(echo "$HTTP_BODY" | jq -r .error)
RL_MESSAGE=$(echo "$HTTP_BODY" | jq -r .message)
expect_eq "the 429 body's error field matches the standard shape" "Too Many Requests" "$RL_ERROR"
if [ -n "$RL_MESSAGE" ] && [ "$RL_MESSAGE" != "null" ]; then
  record PASS "the 429 body carries a real message field" "message=$RL_MESSAGE"
else
  record FAIL "the 429 body carries a real message field" "message=$RL_MESSAGE"
fi

################################################################################
echo "=== Phase D: the register bucket is untouched by login's exhaustion (independent buckets) ==="
################################################################################

INDEP_USER="rl${RUN}indep"
req POST /api/v1/register "{\"username\":\"$INDEP_USER\",\"email\":\"${INDEP_USER}@example.com\",\"password\":\"$PASSWORD\"}"
expect_status "a fresh registration still succeeds while login is exhausted" "200" "$HTTP_STATUS" "-"
flush_bucket register

################################################################################
echo "=== Phase E: deleting login's Redis key simulates its window elapsing — the very next attempt recovers ==="
################################################################################

flush_bucket login
req POST /api/v1/access_token "{\"username\":\"$TARGET_USER\",\"password\":\"wrong-password\"}"
expect_status "after the bucket key is deleted, login is accepted again (401, not 429)" "401" "$HTTP_STATUS" "-"
flush_bucket login

################################################################################
echo "=== Phase F: exceeding register's capacity (${REGISTER_CAPACITY}/hour) returns 429, not 200 ==="
################################################################################

for i in $(seq 1 "$REGISTER_CAPACITY"); do
  U="rl${RUN}reg${i}"
  req POST /api/v1/register "{\"username\":\"$U\",\"email\":\"${U}@example.com\",\"password\":\"$PASSWORD\"}"
  expect_status "registration $i/$REGISTER_CAPACITY is within capacity (200)" "200" "$HTTP_STATUS" "-"
done

OVER_USER="rl${RUN}regover"
req POST /api/v1/register "{\"username\":\"$OVER_USER\",\"email\":\"${OVER_USER}@example.com\",\"password\":\"$PASSWORD\"}"
expect_status "registration $((REGISTER_CAPACITY+1)) exceeds capacity (429)" "429" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase G: the login bucket is untouched by register's exhaustion (independent buckets, reciprocal check) ==="
################################################################################

req POST /api/v1/access_token "{\"username\":\"$TARGET_USER\",\"password\":\"wrong-password\"}"
expect_status "login still works (401, not 429) while register is exhausted" "401" "$HTTP_STATUS" "-"

################################################################################
echo "=== Phase H: deleting register's Redis key simulates its window elapsing — the very next attempt recovers ==="
################################################################################

flush_bucket register
req POST /api/v1/register "{\"username\":\"$OVER_USER\",\"email\":\"${OVER_USER}@example.com\",\"password\":\"$PASSWORD\"}"
expect_status "after the bucket key is deleted, registration is accepted again (200, not 429)" "200" "$HTTP_STATUS" "-"

################################################################################
echo
echo "======================================================"
echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "======================================================"
[ "$FAIL_COUNT" -eq 0 ]
