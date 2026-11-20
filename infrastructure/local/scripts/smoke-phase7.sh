#!/usr/bin/env bash
# =====================================================================================
# Phase 7 end-to-end smoke test — real-time updates (Server-Sent Events), through the API gateway.
# Prereq: cd infrastructure/local && docker compose --profile core --profile catalog --profile inventory \
#           --profile cart --profile orders --profile realtime-ha up -d --build
#   (realtime-ha = notification-service + a second replica on port 8123; with --profile realtime only, the
#    two-replica check is skipped). Payments use the built-in simulator (no Razorpay keys in .env).
# Run (Git Bash):  bash infrastructure/local/scripts/smoke-phase7.sh
# Exit code 0 = every check passed. Safe to re-run: every run uses its own users, store and names.
# =====================================================================================
set -uo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
REPLICA_A="${NOTIFICATION_A_URL:-http://localhost:8113}"
REPLICA_B="${NOTIFICATION_B_URL:-http://localhost:8123}"
ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-admin@grocery.local}"
ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-Admin@12345}"
RUN="$(date +%s)"
PASS=0; FAIL=0; SKIP=0
PAR="$(mktemp -d)"
declare -A STREAM_PID=()

ok()   { echo "  ✔ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ✘ $1"; echo "    → $2" | head -c 900; echo; FAIL=$((FAIL+1)); }
skip() { echo "  – $1 (skipped: $2)"; SKIP=$((SKIP+1)); }
json() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
num()  { grep -o "\"$1\":-\?[0-9.]*" | head -1 | cut -d: -f2; }
claims() { local p; p=$(echo "$1" | cut -d. -f2 | tr '_-' '/+'); while [ $(( ${#p} % 4 )) -ne 0 ]; do p="$p="; done; echo "$p" | base64 -d 2>/dev/null; }
call() {  # method path token [body] [extra header] -> "STATUS BODY"
  local method=$1 path=$2 token=${3:-} body=${4:-} header=${5:-}
  local out; out=$(mktemp)
  local args=(-s -o "$out" -w '%{http_code}' -X "$method" "$GW$path" -H "X-Correlation-Id: smoke7-$RUN-$RANDOM")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  [ -n "$header" ] && args+=(-H "$header")
  local status; status=$(curl "${args[@]}"); echo "$status $(cat "$out")"; rm -f "$out"
}
body() { cut -d' ' -f2-; }
expect() { local status=${3%% *}; if [ "$status" = "$2" ]; then ok "$1 ($status)"; else bad "$1 (expected $2, got $status)" "${3#* }"; fi; }
poll() { # "label" maxSeconds "regex" method path [token]
  local label=$1 max=$2 pattern=$3 method=$4 path=$5 token=${6:-} r
  for _ in $(seq 1 $((max * 4))); do
    r=$(call "$method" "$path" "$token"); if echo "$r" | grep -Eq -- "$pattern"; then ok "$label"; LAST="$r"; return 0; fi
    sleep 0.25
  done
  bad "$label" "$r"; LAST="$r"; return 1
}
register() { curl -s ${4:+-c "$4"} -X POST "$GW/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"Secret123\",\"fullName\":\"$3\",\"phone\":\"+919876543210\",\"role\":\"$2\"}" | json accessToken; }
refresh() { curl -s -b "$1" -c "$1" -X POST "$GW/api/auth/refresh" -H 'X-Requested-With: XMLHttpRequest' | json accessToken; }
login() { call POST /api/auth/login "" "{\"email\":\"$1\",\"password\":\"$2\"}" | body | json accessToken; }
key() { echo "smoke7-$RUN-$1"; }

# ---- SSE helpers: every stream is a background curl writing to $PAR/<name>.sse
open_stream() { # name token [baseUrl] [lastEventId]
  local name=$1 token=$2 base=${3:-$GW} last=${4:-}
  local args=(-sN --max-time 900 -H "Authorization: Bearer $token" -H 'Accept: text/event-stream')
  [ -n "$last" ] && args+=(-H "Last-Event-ID: $last")
  : > "$PAR/$name.sse"
  curl "${args[@]}" "$base/api/notifications/stream" > "$PAR/$name.sse" 2>/dev/null &
  STREAM_PID[$name]=$!
}
close_stream() { local pid=${STREAM_PID[$1]:-}; [ -n "$pid" ] && kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null; STREAM_PID[$1]=""; }
stream_running() { local pid=${STREAM_PID[$1]:-}; [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; }
# await "label" name maxSeconds regex -> waits until the stream file matches; prints elapsed ms on success
await() {
  local label=$1 name=$2 max=$3 pattern=$4 start; start=$(date +%s%N)
  for _ in $(seq 1 $((max * 10))); do
    if grep -Eq -- "$pattern" "$PAR/$name.sse"; then ok "$label ($(( ($(date +%s%N) - start) / 1000000 )) ms)"; return 0; fi
    sleep 0.1
  done
  bad "$label" "$(tail -c 600 "$PAR/$name.sse")"; return 1
}
both() { echo "($1.*$2|$2.*$1)"; }                 # two facts on one line, in either order
waitfor() { local name=$1 max=$2 pattern=$3; for _ in $(seq 1 $((max * 10))); do grep -Eq -- "$pattern" "$PAR/$name.sse" && return 0; sleep 0.1; done; return 1; }
absent() { if grep -Eq -- "$3" "$PAR/$2.sse"; then bad "$1" "$(grep -E -- "$3" "$PAR/$2.sse" | head -3)"; else ok "$1"; fi; }
# ids of notifications (in stream order) whose data matches a regex
notification_ids() { awk -v pat="$2" '/^id: ?/{sub(/^id: ?/,""); id=$0} /^data: ?/ && $0 ~ pat {print id}' "$PAR/$1.sse"; }
last_id() { grep -E '^id: ?' "$PAR/$1.sse" | tail -1 | sed -E 's/^id: ?//' | tr -d '\r'; }

cleanup() { for n in "${!STREAM_PID[@]}"; do close_stream "$n"; done; rm -rf "$PAR"; }
trap cleanup EXIT

echo "== Health and authentication"
expect "health/notification" 200 "$(call GET /api/health/notification)"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$GW/api/notifications/stream")
[ "$code" = 401 ] && ok "the stream needs a token (401)" || bad "stream without token" "got $code"
ATOKEN=$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD"); [ -n "$ATOKEN" ] && ok "admin login" || bad "admin login" "no token"
open_stream admin "$ATOKEN"
await "a stream opens with the retry hint" admin 5 '^retry: ?3000'
grep -qi 'text/event-stream' <(curl -s -D - -o /dev/null --max-time 2 -H "Authorization: Bearer $ATOKEN" "$GW/api/notifications/stream" 2>/dev/null) \
  && ok "Content-Type text/event-stream through the gateway" || bad "content type" "not text/event-stream"

echo "== Approvals reach admins and the owner, live"
vendor_draft() { # email name jar -> "token vendorId"
  local t v; t=$(register "$1" VENDOR "$2" "$3")
  for _ in $(seq 1 60); do v=$(call GET /api/vendors/me "$t" | body | json id); [ -n "$v" ] && break; sleep 0.25; done
  call PUT /api/vendors/me "$t" '{"businessName":"'"$2"'","legalName":"'"$2"' Pvt Ltd","gstin":"29ABCDE1234F1Z5","fssaiLicense":"12345678901234","contactEmail":"ops@live.in","contactPhone":"+919876543210","address":{"line1":"4 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.97,"longitude":77.59}}' >/dev/null
  echo "$t $v"
}
AJAR="$(mktemp)"; BJAR="$(mktemp)"
read -r VATOKEN VA <<< "$(vendor_draft "vendor7a-$RUN@example.com" "Live Mart A $RUN" "$AJAR")"
read -r VBTOKEN VB <<< "$(vendor_draft "vendor7b-$RUN@example.com" "Live Mart B $RUN" "$BJAR")"
[ -n "$VA" ] && [ -n "$VB" ] && ok "two vendor drafts" || bad "vendor drafts" "$VA / $VB"
open_stream va "$VATOKEN"; open_stream vb "$VBTOKEN"
await "vendor A's stream is open" va 5 '^retry'
call POST /api/vendors/me/submit "$VATOKEN" >/dev/null
await "admin: 'waiting for approval' notification" admin 8 "$(both '"type":"APPROVAL_REQUESTED"' "Live Mart A $RUN")"
await "admin: APPROVALS_CHANGED signal" admin 5 '"type":"APPROVALS_CHANGED"'
call POST /api/admin/vendors/$VA/approve "$ATOKEN" >/dev/null
await "vendor A: account approved, live" va 8 '"type":"ACCOUNT_APPROVED"'
await "vendor A: ACCOUNT_UPDATED signal (token refresh)" va 5 '"type":"ACCOUNT_UPDATED"'
absent "vendor B saw nothing of vendor A's approval" vb '"type":"ACCOUNT_APPROVED"|APPROVAL_REQUESTED'
call POST /api/vendors/me/submit "$VBTOKEN" >/dev/null; call POST /api/admin/vendors/$VB/approve "$ATOKEN" >/dev/null
await "vendor B: its own approval" vb 8 '"type":"ACCOUNT_APPROVED"'
for _ in $(seq 1 20); do VATOKEN=$(refresh "$AJAR"); claims "$VATOKEN" | grep -q "\"vendor_id\":\"$VA\"" && break; sleep 0.5; done
for _ in $(seq 1 20); do VBTOKEN=$(refresh "$BJAR"); claims "$VBTOKEN" | grep -q "\"vendor_id\":\"$VB\"" && break; sleep 0.5; done
claims "$VATOKEN" | grep -q "\"vendor_id\":\"$VA\"" && claims "$VBTOKEN" | grep -q "\"vendor_id\":\"$VB\"" && ok "both vendors refreshed their claims" || bad "vendor claims" "$(claims "$VATOKEN")"

echo "== Setup: products, prices, stock and a customer"
CAT=$(call POST /api/admin/categories "$ATOKEN" "{\"name\":\"Smoke Live $RUN\"}" | body | json id)
product() { # token name -> id (published, priced, stocked 20)
  local id; id=$(call POST /api/vendor/products "$1" '{"name":"'"$2"'","categoryId":"'"$CAT"'","unitValue":1,"unitOfMeasure":"KG","foodType":"VEG"}' | body | json id)
  call POST /api/vendor/products/$id/publish "$1" >/dev/null
  for _ in $(seq 1 60); do [ "$(call GET /api/vendor/inventory/$id "$1" | cut -d' ' -f1)" = 200 ] && [ "$(call GET /api/vendor/prices/$id "$1" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
  call PUT /api/vendor/prices/$id "$1" '{"mrp":120.00,"sellingPrice":100.00}' >/dev/null
  call POST /api/vendor/inventory/$id/adjustments "$1" '{"type":"SET","quantity":20,"reason":"STOCK_COUNT"}' >/dev/null
  echo "$id"
}
PA=$(product "$VATOKEN" "Live Rice A"); PB=$(product "$VBTOKEN" "Live Dal B")
poll "vendor A's product is searchable and in stock" 15 '"inStock":true' GET /api/catalog/products/$PA
CTOKEN=$(register "cust7-$RUN@example.com" CUSTOMER "Live Shopper")
for _ in $(seq 1 60); do [ "$(call GET /api/customers/me "$CTOKEN" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
ADDR=$(call POST /api/customers/me/addresses "$CTOKEN" '{"label":"Home","recipientName":"Live Shopper","phone":"+919876543210","line1":"1 Test Street","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.975,"longitude":77.60}' | body | json id)
[ -n "$PA" ] && [ -n "$PB" ] && [ -n "$ADDR" ] && ok "two products and a customer with an address" || bad "setup" "$PA $PB $ADDR"
open_stream cust "$CTOKEN"
await "customer's stream is open" cust 5 '^retry'

echo "== Acceptance: an out-of-stock change reaches the customer's page without a refresh"
call POST /api/vendor/inventory/$PA/adjustments "$VATOKEN" '{"type":"SET","quantity":0,"reason":"STOCK_COUNT"}' >/dev/null
await "customer: STOCK_CHANGED out of stock for the product" cust 8 "$(both "\"productId\":\"$PA\"" '"inStock":false')"
call POST /api/vendor/inventory/$PA/adjustments "$VATOKEN" '{"type":"SET","quantity":20,"reason":"STOCK_COUNT"}' >/dev/null
await "customer: back in stock" cust 8 "$(both "\"productId\":\"$PA\"" '"inStock":true')"
absent "public signals carry no stock quantities" cust '"type":"STOCK_CHANGED".*"(available|onHand|reserved)"'

echo "== Acceptance: vendor A never sees vendor B's events"
call PUT /api/vendor/inventory/$PB/threshold "$VBTOKEN" '{"lowStockThreshold":5}' >/dev/null
call POST /api/vendor/inventory/$PB/adjustments "$VBTOKEN" '{"type":"SET","quantity":3,"reason":"STOCK_COUNT"}' >/dev/null
await "vendor B: low-stock notification" vb 8 "$(both '"type":"LOW_STOCK"' "Live Dal B")"
await "vendor B: INVENTORY_UPDATED signal" vb 5 "$(both '"type":"INVENTORY_UPDATED"' "$PB")"
sleep 1
# (vendor A may have its OWN low-stock notification: its product went to 0 above)
# (public STOCK_CHANGED signals for B's product are fine: they go to everyone; private ones must not)
PRIVATE_B="\"type\":\"(LOW_STOCK|INVENTORY_UPDATED)\".*(Live Dal B|$PB)|(Live Dal B|$PB).*\"type\":\"(LOW_STOCK|INVENTORY_UPDATED)\""
absent "vendor A's stream has none of vendor B's private stock notifications or signals" va "$PRIVATE_B"
r=$(call GET "/api/notifications?size=50" "$VATOKEN"); echo "$r" | grep -q "Live Dal B\|$PB" && bad "vendor A's inbox" "$r" || ok "vendor A's inbox has none of vendor B's notifications"

echo "== Live order updates for customer, store and admins"
call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$PA"'","quantity":2}' >/dev/null
total() { call GET "/api/cart/preview?addressId=$ADDR" "$CTOKEN" | body | grep -o '"total":[0-9.]*' | tail -1 | cut -d: -f2; }
pay() { # orderId outcome
  local r; r=$(call POST /api/payments/simulator/orders/$1/pay "$CTOKEN" "{\"outcome\":\"$2\"}")
  [ "$2" != SUCCESS ] && { echo "$r"; return; }
  local b; b=$(echo "$r" | body)
  call POST /api/payments/razorpay/verify "$CTOKEN" "{\"razorpayOrderId\":\"$(echo "$b" | json razorpayOrderId)\",\"razorpayPaymentId\":\"$(echo "$b" | json razorpayPaymentId)\",\"razorpaySignature\":\"$(echo "$b" | json razorpaySignature)\"}"
}
place_order() { # key -> orderId (waits until payable)
  local r o t; t=$(total)
  # A lost response (000) or a 5xx is retried with the SAME Idempotency-Key: at most one order is ever created.
  for _ in 1 2 3; do
    r=$(call POST /api/orders "$CTOKEN" "{\"addressId\":\"$ADDR\",\"expectedTotal\":$t}" "Idempotency-Key: $(key "$1")")
    case "${r%% *}" in 000|5*) sleep 1 ;; *) break ;; esac
  done
  o=$(echo "$r" | body | json id)
  [ -z "$o" ] && { echo "    (placing order $1 failed: $(echo "$r" | cut -c1-200))" >&2; return; }
  for _ in $(seq 1 60); do [ "$(call GET /api/payments/orders/$o/checkout "$CTOKEN" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
  # OrderCreated empties the cart asynchronously: wait for it, so the next cart starts empty (no race with the clear)
  for _ in $(seq 1 60); do call GET /api/cart "$CTOKEN" | grep -q '"store":null' && break; sleep 0.25; done
  echo "$o"
}
O1=$(place_order order-1); ON1=$(call GET /api/orders/$O1 "$CTOKEN" | body | json orderNumber)
[ -n "$O1" ] && ok "order $ON1 placed and payable" || bad "place order" "no order id"
await "customer: ORDER_UPDATED while the order moves to AWAITING_PAYMENT" cust 8 "$(both "\"orderId\":\"$O1\"" AWAITING_PAYMENT)"
pay "$O1" FAIL >/dev/null
await "customer: payment declined notification" cust 8 "$(both '"type":"PAYMENT_FAILED"' "$ON1")"
pay "$O1" SUCCESS >/dev/null
await "customer: order confirmed notification" cust 8 "$(both '"type":"ORDER_CONFIRMED"' "$ON1")"
await "vendor A: new order notification" va 8 "$(both '"type":"NEW_ORDER"' "$ON1")"
await "admin: ADMIN_ORDERS_CHANGED signal" admin 5 "$(both '"type":"ADMIN_ORDERS_CHANGED"' "$O1")"
absent "vendor B was not told about vendor A's order" vb "$ON1|$O1"
absent "admins got no per-customer notification for the order" admin "\"type\":\"ORDER_CONFIRMED\""

echo "== Inbox"
r=$(call GET "/api/notifications?size=20" "$CTOKEN")
echo "$r" | grep -q '"type":"ORDER_CONFIRMED"' && echo "$r" | grep -q '"type":"PAYMENT_FAILED"' && ok "the inbox lists the customer's notifications" || bad "inbox" "$r"
N1=$(echo "$r" | body | json id)
c1=$(call GET /api/notifications/unread-count "$CTOKEN" | body | num count)
[ "${c1:-0}" -ge 2 ] && ok "unread count $c1" || bad "unread count" "$c1"
expect "vendor A cannot touch the customer's notification" 404 "$(call POST /api/notifications/$N1/read "$VATOKEN")"
expect "mark one read" 204 "$(call POST /api/notifications/$N1/read "$CTOKEN")"
c2=$(call GET /api/notifications/unread-count "$CTOKEN" | body | num count)
[ "$c2" = $((c1 - 1)) ] && ok "unread count drops to $c2" || bad "unread after read" "$c1 → $c2"
call POST /api/notifications/read-all "$CTOKEN" >/dev/null
[ "$(call GET /api/notifications/unread-count "$CTOKEN" | body | num count)" = 0 ] && ok "read-all → 0 unread" || bad "read-all" "still unread"

echo "== Acceptance: a reconnect replays the notifications that were missed"
LASTSEEN=$(last_id cust); close_stream cust
[ -n "$LASTSEEN" ] && ok "customer disconnects after notification $LASTSEEN" || bad "last event id" "none"
call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$PA"'","quantity":1}' >/dev/null
O2=$(place_order order-2); ON2=$(call GET /api/orders/$O2 "$CTOKEN" | body | json orderNumber)
pay "$O2" FAIL >/dev/null
call POST /api/orders/$O2/cancel "$CTOKEN" '{"reason":"Changed my mind"}' >/dev/null
poll "the missed notifications are in the inbox" 10 "$(both '"type":"ORDER_CANCELLED"' "$ON2")" GET "/api/notifications?size=5" "$CTOKEN"
open_stream cust2 "$CTOKEN" "$GW" "$LASTSEEN"
await "replay: the declined payment" cust2 8 "$(both '"type":"PAYMENT_FAILED"' "$ON2")"
await "replay: the cancellation" cust2 5 "$(both '"type":"ORDER_CANCELLED"' "$ON2")"
ids=$(grep -E '^id: ?' "$PAR/cust2.sse" | sed -E 's/^id: ?//' | tr -d '\r')
echo "$ids" | grep -qx "$LASTSEEN" && bad "no duplicates" "the last seen notification was sent again" || ok "nothing seen before is sent again"
# "Oldest first" = creation order (UUIDv7 ids sort by time). The decline and the cancellation travel on different
# topics, so either notification may have been created first; the replay must simply be in ascending id order.
[ "$ids" = "$(echo "$ids" | sort)" ] && [ "$(echo "$ids" | grep -c .)" -ge 2 ]   && ok "replayed oldest first (ascending notification ids)" || bad "replay order" "$ids"
close_stream cust2
open_stream cust3 "$CTOKEN" "$GW" "not-a-notification-id"
await "an unknown Last-Event-ID gets RESYNC" cust3 5 '"type":"RESYNC"'
close_stream cust3

echo "== Acceptance: with two replicas, a user on replica A gets events processed by replica B"
if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$REPLICA_B/actuator/health")" != 200 ]; then
  skip "two-replica fan-out" "notification-service-2 is not running (start with --profile realtime-ha)"
else
  open_stream ra "$CTOKEN" "$REPLICA_A"; open_stream rb "$CTOKEN" "$REPLICA_B"
  await "stream on replica A" ra 5 '^retry'; await "stream on replica B" rb 5 '^retry'
  members=$(docker exec grocery-kafka-1 sh -c '/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group notification-service.dispatch --members 2>/dev/null' | awk 'NR>1 && $NF+0>0' | wc -l)
  [ "$members" -ge 2 ] && ok "both replicas consume (2 members with partitions)" || bad "consumer group members" "$members"
  NUMS=""
  for i in 1 2 3 4; do
    call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$PA"'","quantity":1}' >/dev/null
    o=$(place_order "multi-$i"); [ -z "$o" ] && continue
    n=$(call GET /api/orders/$o "$CTOKEN" | body | json orderNumber); NUMS="$NUMS $n"
    pay "$o" FAIL >/dev/null; call POST /api/orders/$o/cancel "$CTOKEN" '{}' >/dev/null
  done
  for n in $NUMS; do
    waitfor ra 10 "$(both '"type":"ORDER_CANCELLED"' "$n")"; waitfor rb 10 "$(both '"type":"ORDER_CANCELLED"' "$n")"
  done
  placed=$(echo $NUMS | wc -w)
  a=$(grep -c '"type":"ORDER_CANCELLED"' "$PAR/ra.sse"); b=$(grep -c '"type":"ORDER_CANCELLED"' "$PAR/rb.sse")
  [ "$placed" -ge 3 ] && [ "$a" = "$placed" ] && [ "$b" = "$placed" ]     && ok "$placed orders (different partitions): every notification reached both replicas' streams" || bad "fan-out" "placed $placed, replica A $a, replica B $b"
  [ "$(notification_ids ra ORDER_CANCELLED | sort | tr '\n' ' ')" = "$(notification_ids rb ORDER_CANCELLED | sort | tr '\n' ' ')" ] \
    && ok "same notification ids on both replicas" || bad "ids differ" "$(notification_ids ra ORDER_CANCELLED) vs $(notification_ids rb ORDER_CANCELLED)"
  close_stream ra; close_stream rb
fi

echo "== Suspension ends the live session"
expect "admin suspends vendor B" 200 "$(call POST /api/admin/vendors/$VB/suspend "$ATOKEN" '{"reason":"smoke test"}')"
await "vendor B: session REVOKED" vb 8 '"reason":"REVOKED"'
for _ in $(seq 1 30); do stream_running vb || break; sleep 0.2; done
stream_running vb && bad "vendor B's stream closed" "still open" || ok "vendor B's stream was closed by the server"
stream_running va && ok "vendor A's stream is still open" || bad "vendor A's stream" "closed"
grep -q '^: \?hb' "$PAR/admin.sse" && ok "heartbeats on a long-lived stream" || skip "heartbeat" "the run was shorter than one 20 s heartbeat"

echo "== Internal endpoints stay internal"
expect "/internal/** is not routed by the gateway" 404 "$(call GET /internal/vendors/$VA/store "$CTOKEN")"

rm -f "$AJAR" "$BJAR"
echo
echo "Passed: $PASS   Failed: $FAIL   Skipped: $SKIP"
[ "$FAIL" -eq 0 ]
