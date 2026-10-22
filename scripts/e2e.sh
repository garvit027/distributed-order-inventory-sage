#!/usr/bin/env bash
# scripts/e2e.sh — end-to-end verification of docs/CONTRACT.md §4 (all 5 scenarios).
#
# Assumes `docker compose up -d --build` has been run (Jaeger UI on 16686,
# order-service REST on localhost:8080, or $ORDER_HTTP_PORT when overridden).
# Prints PASS/FAIL per scenario and
# exits non-zero if any scenario failed. Polls use a 30s timeout.
#
# Stock reads go through scripts/get_stock.py, executed inside the inventory
# container (only order-service publishes a host port in compose).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PY="${ROOT}/.venv/bin/python"
# Host port the order REST edge is published on (see docker-compose.yaml).
ORDER_URL="http://localhost:${ORDER_HTTP_PORT:-8080}"
JAEGER_URL="http://localhost:16686"
POLL_TIMEOUT=30

TOTAL_PASS=0
TOTAL_FAIL=0
SC_FAILS=0
SC_NAME=""

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

log()  { printf '%s\n' "$*"; }
die()  { printf '\nE2E ABORT: %s\n' "$*" >&2; exit 2; }

# ---------------------------------------------------------------- helpers ----

sc_start() { SC_NAME="$1"; SC_FAILS=0; log ""; log "--- $SC_NAME ---"; }
sc_end() {
  if (( SC_FAILS == 0 )); then
    TOTAL_PASS=$((TOTAL_PASS + 1))
    log "PASS: $SC_NAME"
  else
    TOTAL_FAIL=$((TOTAL_FAIL + 1))
    log "FAIL: $SC_NAME ($SC_FAILS assertion(s) failed)"
  fi
}

# check_eq <message> <expected> <actual>
check_eq() {
  if [[ "$2" == "$3" ]]; then
    log "  ok    $1"
  else
    log "  FAIL  $1 — expected '$2', got '$3'"
    SC_FAILS=$((SC_FAILS + 1))
  fi
}

check_true() { # check_true <message> <rc (0 = pass)>
  if (( $2 == 0 )); then
    log "  ok    $1"
  else
    log "  FAIL  $1"
    SC_FAILS=$((SC_FAILS + 1))
  fi
}

# jget <json> <dotted.path> — tiny JSON reader backed by the repo venv python.
jget() {
  "$PY" -c '
import json, sys
try:
    data = json.loads(sys.argv[1])
    for part in sys.argv[2].split("."):
        if part == "":
            continue
        data = data[int(part)] if isinstance(data, list) else data.get(part)
        if data is None:
            break
except Exception:
    data = None
print("" if data is None else data)
' "$1" "$2"
}

# wait_http <url> <timeout-seconds> — poll until curl -sf succeeds.
wait_http() {
  local url=$1 timeout=${2:-$POLL_TIMEOUT}
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    curl -sf -o /dev/null "$url" 2>/dev/null && return 0
    sleep 1
  done
  return 1
}

# stock <sku> — prints "available reserved"; retries up to POLL_TIMEOUT.
stock() {
  local sku=$1 out deadline=$((SECONDS + POLL_TIMEOUT))
  while (( SECONDS < deadline )); do
    # Preferred: run the helper inside the inventory container (gRPC 50051 in-network).
    if out=$(docker compose exec -T inventory python /tmp/get_stock.py "$sku" \
        --host localhost --port 50051 2>/dev/null); then
      printf '%s' "$out"; return 0
    fi
    # Fallback: host-side helper (works if inventory gRPC is published, e.g. local dev).
    if [[ -x "$PY" ]] && out=$("$PY" "$ROOT/scripts/get_stock.py" "$sku" 2>/dev/null); then
      printf '%s' "$out"; return 0
    fi
    sleep 1
  done
  return 1
}

# post_order <idempotency-key> <body> → sets POST_CODE (HTTP status) and POST_BODY.
post_order() {
  local key=$1 body=$2
  POST_CODE=$(curl -s -o "$TMP/resp.json" -w '%{http_code}' \
    -X POST "$ORDER_URL/api/orders" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $key" \
    --data "$body" 2>/dev/null)
  POST_BODY=$(cat "$TMP/resp.json" 2>/dev/null || true)
  [[ -n "$POST_CODE" ]] || POST_CODE="000"
}

# poll_order <orderId> — poll until CONFIRMED/FAILED or POLL_TIMEOUT; echoes JSON, rc 1 on timeout.
poll_order() {
  local id=$1 json="" status="" deadline=$((SECONDS + POLL_TIMEOUT))
  while (( SECONDS < deadline )); do
    if json=$(curl -sf "$ORDER_URL/api/orders/$id" 2>/dev/null); then
      status=$(jget "$json" status)
      case "$status" in
        CONFIRMED|FAILED) printf '%s' "$json"; return 0 ;;
      esac
    fi
    sleep 1
  done
  printf '%s' "$json"
  return 1
}

# jaeger_has_span <service> <needle> — poll Jaeger until some span operation contains needle (case-insensitive).
jaeger_has_span() {
  local svc=$1 needle=$2 deadline=$((SECONDS + POLL_TIMEOUT)) body
  while (( SECONDS < deadline )); do
    if body=$(curl -sf "$JAEGER_URL/api/traces?service=$svc&limit=20" 2>/dev/null); then
      if printf '%s' "$body" | "$PY" -c '
import json, sys
needle = sys.argv[1].lower()
try:
    data = json.loads(sys.stdin.read())
except Exception:
    sys.exit(1)
for trace in data.get("data") or []:
    for span in trace.get("spans") or []:
        if needle in (span.get("operationName") or "").lower():
            sys.exit(0)
sys.exit(1)
' "$needle"; then
        return 0
      fi
    fi
    sleep 2
  done
  return 1
}

# ------------------------------------------------------------- preflight ----

command -v docker >/dev/null 2>&1 \
  || die "docker is not installed / not on PATH."
docker info >/dev/null 2>&1 \
  || die "the Docker daemon is not reachable (on this machine: start colima with 'colima start')."
docker compose version >/dev/null 2>&1 \
  || die "docker compose v2 is not available."
[[ -f "$ROOT/docker-compose.yaml" ]] || die "docker-compose.yaml not found in $ROOT."

RUNNING=$(docker compose ps --status running -q order 2>/dev/null)
[[ -n "$RUNNING" ]] \
  || die "the compose stack is not running. Start it with: make up   (check 'docker compose ps' / 'docker compose logs')."

log "Waiting for order-service REST edge at $ORDER_URL ..."
wait_http "$ORDER_URL/actuator/health" 60 \
  || die "order-service did not become healthy within 60s. Check 'docker compose ps' and 'docker compose logs order'."

log "Waiting for Jaeger query API at $JAEGER_URL ..."
wait_http "$JAEGER_URL/api/services" 30 \
  || die "Jaeger did not become healthy within 30s. Check 'docker compose logs jaeger'."

log "Installing get_stock.py helper into the inventory container ..."
docker compose cp "$ROOT/scripts/get_stock.py" inventory:/tmp/get_stock.py >/dev/null 2>&1 \
  || die "could not copy the stock helper into the inventory container."

log "Waiting for inventory gRPC and seeded stock levels ..."
red_seed=$(stock WIDGET-RED) || die "could not read WIDGET-RED stock (inventory gRPC down?)"
blue_seed=$(stock WIDGET-BLUE) || die "could not read WIDGET-BLUE stock (inventory gRPC down?)"
if [[ "$red_seed" != "10 0" || "$blue_seed" != "3 0" ]]; then
  die "stock is not at the seeded levels (expected '10 0' and '3 0', got '$red_seed' and '$blue_seed'). Reset with: docker compose down -v && make up"
fi

BODY_HAPPY='{"customerId":"cus_ok","contact":"buyer@example.com","currency":"USD","amountCents":2500,"items":[{"sku":"WIDGET-RED","quantity":2}]}'
BODY_DECLINE='{"customerId":"cus_decline","contact":"buyer@example.com","currency":"USD","amountCents":2500,"items":[{"sku":"WIDGET-RED","quantity":1}]}'
BODY_BLUE='{"customerId":"cus_ok","currency":"USD","amountCents":2500,"items":[{"sku":"WIDGET-BLUE","quantity":999}]}'
BODY_CONFLICT='{"customerId":"cus_ok","contact":"buyer@example.com","currency":"USD","amountCents":2500,"items":[{"sku":"WIDGET-RED","quantity":3}]}'

# ------------------------------------------------------------- scenario 1 ----

sc_start "1. Happy path: CONFIRMED, WIDGET-RED 10->8, idempotent replay 200/same orderId"

red=$(stock WIDGET-RED); avail_before=${red%% *}
check_eq "stock WIDGET-RED available before order" "10" "$avail_before"

post_order "e2e-happy-1" "$BODY_HAPPY"
check_eq "POST /api/orders returns 201" "201" "$POST_CODE"
order_id=$(jget "$POST_BODY" orderId)
check_true "response contains an orderId" "$([[ -n "$order_id" ]] && echo 0 || echo 1)"

json=$(poll_order "$order_id")
status=$(jget "$json" status)
check_eq "order reaches terminal status CONFIRMED" "CONFIRMED" "$status"

red=$(stock WIDGET-RED); avail_after=${red%% *}
check_eq "stock WIDGET-RED available after order (10->8)" "8" "$avail_after"

post_order "e2e-happy-1" "$BODY_HAPPY"
check_eq "replay with same key + same body returns 200" "200" "$POST_CODE"
replay_id=$(jget "$POST_BODY" orderId)
check_eq "replay returns the same orderId" "$order_id" "$replay_id"

sc_end

# ------------------------------------------------------------- scenario 2 ----

sc_start "2. Compensating release: cus_decline -> FAILED/PAYMENT_DECLINED, stock back to 8"

post_order "e2e-decline-1" "$BODY_DECLINE"
check_eq "POST /api/orders returns 201" "201" "$POST_CODE"
order_id=$(jget "$POST_BODY" orderId)
check_true "response contains an orderId" "$([[ -n "$order_id" ]] && echo 0 || echo 1)"

json=$(poll_order "$order_id")
status=$(jget "$json" status)
reason=$(jget "$json" failureReason)
check_eq "order reaches terminal status FAILED" "FAILED" "$status"
check_eq "failureReason is PAYMENT_DECLINED" "PAYMENT_DECLINED" "$reason"

red=$(stock WIDGET-RED); avail=${red%% *}
check_eq "stock WIDGET-RED restored to 8 (reserve compensated)" "8" "$avail"

sc_end

# ------------------------------------------------------------- scenario 3 ----

sc_start "3. No oversell: WIDGET-BLUE x999 -> FAILED/INSUFFICIENT_STOCK, stock unchanged at 3"

blue=$(stock WIDGET-BLUE); before=${blue%% *}
check_eq "stock WIDGET-BLUE available before order" "3" "$before"

post_order "e2e-blue-1" "$BODY_BLUE"
check_eq "POST /api/orders returns 201" "201" "$POST_CODE"
order_id=$(jget "$POST_BODY" orderId)
check_true "response contains an orderId" "$([[ -n "$order_id" ]] && echo 0 || echo 1)"

json=$(poll_order "$order_id")
status=$(jget "$json" status)
reason=$(jget "$json" failureReason)
check_eq "order reaches terminal status FAILED" "FAILED" "$status"
check_eq "failureReason is INSUFFICIENT_STOCK" "INSUFFICIENT_STOCK" "$reason"

blue=$(stock WIDGET-BLUE); after=${blue%% *}
check_eq "stock WIDGET-BLUE available unchanged" "3" "$after"

sc_end

# ------------------------------------------------------------- scenario 4 ----

sc_start "4. Idempotency conflict: same key as scenario 1 with a different body -> HTTP 409"

post_order "e2e-happy-1" "$BODY_CONFLICT"
check_eq "POST with reused key + different body returns 409" "409" "$POST_CODE"
code=$(jget "$POST_BODY" error.code)
log "  info  error.code=$code"

sc_end

# ------------------------------------------------------------- scenario 5 ----

sc_start "5. Tracing: saga spans exported to Jaeger for orchestrator and order-service"

jaeger_has_span "orchestrator" "reserve_inventory"
check_true "orchestrator has a trace with a RESERVE_INVENTORY span" "$?"

jaeger_has_span "order-service" "saga"
check_true "order-service has a trace with a saga span (StartSaga)" "$?"

sc_end

# ---------------------------------------------------------------- summary ----

log ""
log "=============================================="
log "E2E result: $TOTAL_PASS scenario(s) passed, $TOTAL_FAIL failed."
log "=============================================="

if (( TOTAL_FAIL > 0 )); then
  exit 1
fi
exit 0
