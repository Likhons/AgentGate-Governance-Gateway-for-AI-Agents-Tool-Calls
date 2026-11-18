#!/usr/bin/env bash
# =============================================================================
# Live quality-attribute checks against the running Docker Compose stack (C5)
#
#   qa2  k6: 100 calls/s for 60 s, gateway overhead p(95) < 30 ms
#   qa1  k6: 30 red-team calls blocked or held, 200 legitimate calls pass
#   qa4  PostgreSQL outage drill (docker pause): LOW-risk calls keep working,
#        MEDIUM/HIGH-risk calls fail closed, and calls made during the outage
#        are hash-chained in the audit trail once the database is back
#   qa3  the audit hash chain verifies intact
#
# Start the stack first:   docker compose --profile benchmark up -d --build --wait
# Run every check:         bash load-tests/run-live-qa.sh
# Run some checks:         bash load-tests/run-live-qa.sh qa1 qa3
# qa4 uses the tools and policies that the qa1 suite registers, so run qa1 first
# on a fresh stack. Admin credentials come from .env (AGENTGATE_ADMIN_USERNAME /
# AGENTGATE_ADMIN_PASSWORD) or the ADMIN_USER / ADMIN_PASS environment variables.
# Output is written to build/live-qa/. Exits non-zero if any check fails.
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

RESULTS_DIR="build/live-qa"
BASE_URL="${AGENTGATE_URL:-http://localhost:8080}"
APP_CONTAINER="${APP_CONTAINER:-agentgate-app}"
DB_CONTAINER="${DB_CONTAINER:-agentgate-postgres}"
# Demo agent registered by the setup() of both k6 suites
AGENT_ID="support-bot"
AGENT_SECRET="support-secret-2026"

env_value() { grep -E "^$1=" .env 2>/dev/null | head -1 | cut -d= -f2- || true; }
ADMIN_USER="${ADMIN_USER:-$(env_value AGENTGATE_ADMIN_USERNAME)}"
ADMIN_USER="${ADMIN_USER:-user}"
ADMIN_PASS="${ADMIN_PASS:-$(env_value AGENTGATE_ADMIN_PASSWORD)}"
if [ -z "$ADMIN_PASS" ]; then
  echo "Set AGENTGATE_ADMIN_PASSWORD in .env (or export ADMIN_PASS)" >&2
  exit 2
fi
export ADMIN_USER ADMIN_PASS

NETWORK="$(docker inspect "$APP_CONTAINER" \
  --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' | awk '{print $1}')"
mkdir -p "$RESULTS_DIR"
failures=0

pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; failures=$((failures + 1)); }

admin_get() { curl -s --max-time 60 -u "$ADMIN_USER:$ADMIN_PASS" "$BASE_URL$1" || true; }

# Jackson 3 sorts properties, so "details" precedes "status": take the first status after "policyStore"
policy_store_status() {
  admin_get /actuator/health | grep -o '"policyStore":{.*' | grep -o '"status":"[A-Z_]*"' | head -1 | cut -d'"' -f4 || true
}

agent_token() {
  curl -s --max-time 10 -X POST "$BASE_URL/oauth2/token" \
    -d "grant_type=client_credentials&client_id=$AGENT_ID&client_secret=$AGENT_SECRET&delegated_user=cust-100" \
    | sed -E 's/.*"access_token":"([^"]+)".*/\1/'
}

# Prints "<request ms> <http status> <outcome, or 'fail closed'>"; the time is curl's own request time
gateway_call() {
  local response meta
  response="$(curl -s --max-time 15 -w '\n%{http_code} %{time_total}' -X POST "$BASE_URL/api/v1/gateway/call" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$2" || true)"
  meta="$(echo "$response" | tail -1)"
  echo "$(echo "$meta" | awk '{printf "%d", $2 * 1000}') $(echo "$meta" | cut -d' ' -f1) $(echo "$response" \
    | head -n -1 | grep -o '"outcome":"[A-Z_]*"\|fail closed' | head -1 | sed 's/"outcome":"//; s/"//g' || true)"
}

run_k6() {
  echo "== $1: k6 $2"
  if docker run --rm -i --network "$NETWORK" -e AGENTGATE_URL=http://agentgate:8080 \
      -e ADMIN_USER -e ADMIN_PASS grafana/k6 run --quiet --no-color - < "$2" > "$RESULTS_DIR/$1-k6.txt" 2>&1; then
    pass "$1: k6 thresholds met"
  else
    fail "$1: k6 thresholds crossed (see $RESULTS_DIR/$1-k6.txt)"
  fi
  sed -n '/THRESHOLDS/,/TOTAL RESULTS/p' "$RESULTS_DIR/$1-k6.txt" | grep -E "✓|✗" || true
}

qa2() { run_k6 qa2 load-tests/qa2_gateway_latency.k6.js; }
qa1() { run_k6 qa1 load-tests/qa1_security_suite.k6.js; }

qa3() {
  echo "== qa3: audit hash chain verification"
  local report
  report="$(admin_get /api/v1/audit/verify)"
  echo "$report" > "$RESULTS_DIR/qa3-verify.json"
  if echo "$report" | grep -q '"intact":true'; then
    pass "qa3: $(echo "$report" | grep -o '"totalRecordsVerified":[0-9]*') intact"
  else
    fail "qa3: audit chain not intact: $report"
  fi
}

qa4() {
  echo "== qa4: PostgreSQL outage drill (docker pause $DB_CONTAINER)"
  local token outage_user low_ms low medium high deadline
  token="$(agent_token)"
  outage_user="cust-qa4-$(date +%s)"

  # Warm the caches with the same agent and tools while the database is up
  gateway_call "$token" '{"toolName":"lookup_order","delegatedUserId":"cust-100","arguments":{"customerId":"cust-100"}}' > /dev/null
  gateway_call "$token" '{"toolName":"issue_refund","delegatedUserId":"cust-100","arguments":{"orderId":"ORD-QA4-WARM","amount":100}}' > /dev/null
  gateway_call "$token" '{"toolName":"bulk_payout","delegatedUserId":"cust-100","arguments":{"recipient":"vendor-qa4","amount":100}}' > /dev/null

  docker pause "$DB_CONTAINER" > /dev/null
  trap 'docker unpause "$DB_CONTAINER" > /dev/null 2>&1 || true' EXIT
  deadline=$((SECONDS + 30))
  until [ "$(policy_store_status)" = "DOWN" ] || [ "$SECONDS" -ge "$deadline" ]; do sleep 1; done
  echo "policyStore health during the outage: $(policy_store_status)"

  read -r low_ms low <<< "$(gateway_call "$token" "{\"toolName\":\"lookup_order\",\"delegatedUserId\":\"$outage_user\",\"arguments\":{\"customerId\":\"$outage_user\"}}")"
  read -r _ medium <<< "$(gateway_call "$token" '{"toolName":"issue_refund","delegatedUserId":"cust-100","arguments":{"orderId":"ORD-QA4-OUTAGE","amount":100}}')"
  read -r _ high <<< "$(gateway_call "$token" '{"toolName":"bulk_payout","delegatedUserId":"cust-100","arguments":{"recipient":"vendor-qa4-outage","amount":100}}')"

  docker unpause "$DB_CONTAINER" > /dev/null
  trap - EXIT

  [ "$low" = "200 EXECUTED" ] && pass "qa4: LOW-risk call during the outage -> $low in ${low_ms} ms" \
    || fail "qa4: LOW-risk call during the outage -> $low (expected 200 EXECUTED)"
  [ "$medium" = "409 fail closed" ] && pass "qa4: MEDIUM-risk call during the outage -> $medium" \
    || fail "qa4: MEDIUM-risk call during the outage -> $medium (expected 409 fail closed)"
  [ "$high" = "409 fail closed" ] && pass "qa4: HIGH-risk call during the outage -> $high" \
    || fail "qa4: HIGH-risk call during the outage -> $high (expected 409 fail closed)"

  deadline=$((SECONDS + 60))
  until [ "$(policy_store_status)" = "UP" ] || [ "$SECONDS" -ge "$deadline" ]; do sleep 1; done
  until admin_get "/api/v1/audit?delegatedUserId=$outage_user&toolName=lookup_order&outcome=EXECUTED&size=5" \
      | grep -q "\"delegatedUserId\":\"$outage_user\"" || [ "$SECONDS" -ge "$deadline" ]; do sleep 1; done
  if admin_get "/api/v1/audit?delegatedUserId=$outage_user&toolName=lookup_order&outcome=EXECUTED&size=5" \
      | grep -q "\"delegatedUserId\":\"$outage_user\""; then
    pass "qa4: the outage-time call was hash-chained after recovery (policyStore $(policy_store_status))"
  else
    fail "qa4: no audit record for the outage-time call after recovery"
  fi
}

checks=("$@")
[ ${#checks[@]} -gt 0 ] || checks=(qa2 qa1 qa4 qa3)
for check in "${checks[@]}"; do
  case "$check" in
    qa1|qa2|qa3|qa4) "$check" ;;
    *) echo "Unknown check '$check' (use qa1, qa2, qa3, qa4)" >&2; exit 2 ;;
  esac
done

echo "== $failures failed check(s); results in $RESULTS_DIR/"
[ "$failures" -eq 0 ]
