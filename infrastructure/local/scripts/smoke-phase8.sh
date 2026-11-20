#!/usr/bin/env bash
# =====================================================================================
# Phase 8 end-to-end smoke test — vendor & restaurant fulfillment, everything through the API gateway.
# Prereq (short acceptance window so the auto-reject check runs; payments use the built-in simulator):
#   cd infrastructure/local && ACCEPTANCE_TIMEOUT=30s ACCEPTANCE_POLL_INTERVAL=3s docker compose --profile core \
#     --profile catalog --profile inventory --profile cart --profile orders --profile realtime up -d --build
# Run (Git Bash):  bash infrastructure/local/scripts/smoke-phase8.sh
# Exit code 0 = every check passed. Safe to re-run: every run uses its own users, stores and names.
# =====================================================================================
set -uo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
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
  local args=(-s -o "$out" -w '%{http_code}' -X "$method" "$GW$path" -H "X-Correlation-Id: smoke8-$RUN-$RANDOM")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  [ -n "$header" ] && args+=(-H "$header")
  local status; status=$(curl "${args[@]}"); echo "$status $(cat "$out")"; rm -f "$out"
}
body() { cut -d' ' -f2-; }
expect() { local status=${3%% *}; if [ "$status" = "$2" ]; then ok "$1 ($status)"; else bad "$1 (expected $2, got $status)" "${3#* }"; fi; }
has() { if echo "$3" | grep -Eq -- "$2"; then ok "$1"; else bad "$1" "$3"; fi; }
both() { echo "($1.*$2|$2.*$1)"; }
poll() { # "label" maxSeconds "regex" method path [token]
  local label=$1 max=$2 pattern=$3 method=$4 path=$5 token=${6:-} r start; start=$(date +%s%N)
  for _ in $(seq 1 $((max * 4))); do
    r=$(call "$method" "$path" "$token")
    if echo "$r" | grep -Eq -- "$pattern"; then ok "$label ($(( ($(date +%s%N) - start) / 1000000 )) ms)"; LAST="$r"; return 0; fi
    sleep 0.25
  done
  bad "$label" "$r"; LAST="$r"; return 1
}
register() { curl -s ${4:+-c "$4"} -X POST "$GW/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"Secret123\",\"fullName\":\"$3\",\"phone\":\"+919876543210\",\"role\":\"$2\"}" | json accessToken; }
refresh() { curl -s -b "$1" -c "$1" -X POST "$GW/api/auth/refresh" -H 'X-Requested-With: XMLHttpRequest' | json accessToken; }
login() { call POST /api/auth/login "" "{\"email\":\"$1\",\"password\":\"$2\"}" | body | json accessToken; }
key() { echo "smoke8-$RUN-$1"; }
open_stream() { # name token
  : > "$PAR/$1.sse"
  curl -sN --max-time 900 -H "Authorization: Bearer $2" -H 'Accept: text/event-stream' "$GW/api/notifications/stream" > "$PAR/$1.sse" 2>/dev/null &
  STREAM_PID[$1]=$!
}
await() { # "label" name maxSeconds regex
  local label=$1 name=$2 max=$3 pattern=$4 start; start=$(date +%s%N)
  for _ in $(seq 1 $((max * 10))); do
    if grep -Eq -- "$pattern" "$PAR/$name.sse"; then ok "$label ($(( ($(date +%s%N) - start) / 1000000 )) ms)"; return 0; fi
    sleep 0.1
  done
  bad "$label" "$(tail -c 500 "$PAR/$name.sse")"; return 1
}
cleanup() { for n in "${!STREAM_PID[@]}"; do kill "${STREAM_PID[$n]}" 2>/dev/null; done; rm -rf "$PAR"; }
trap cleanup EXIT

echo "== Health"
for s in gateway vendor restaurant order payment notification; do expect "health/$s" 200 "$(call GET /api/health/$s)"; done
ATOKEN=$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD"); [ -n "$ATOKEN" ] && ok "admin login" || bad "admin login" "no token"

echo "== Setup: a vendor, a second vendor, a restaurant (open all day) and a customer"
CAT=$(call POST /api/admin/categories "$ATOKEN" "{\"name\":\"Smoke Fulfil $RUN\"}" | body | json id)
vendor() { # email name jar -> "token vendorId"
  local t v; t=$(register "$1" VENDOR "$2" "$3")
  for _ in $(seq 1 60); do v=$(call GET /api/vendors/me "$t" | body | json id); [ -n "$v" ] && break; sleep 0.25; done
  call PUT /api/vendors/me "$t" '{"businessName":"'"$2"'","legalName":"'"$2"' Pvt Ltd","gstin":"29ABCDE1234F1Z5","fssaiLicense":"12345678901234","contactEmail":"ops@fulfil.in","contactPhone":"+919876543210","address":{"line1":"4 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.97,"longitude":77.59}}' >/dev/null
  call POST /api/vendors/me/submit "$t" >/dev/null; call POST /api/admin/vendors/$v/approve "$ATOKEN" >/dev/null
  for _ in $(seq 1 20); do t=$(refresh "$3"); claims "$t" | grep -q "\"vendor_id\":\"$v\"" && break; sleep 0.5; done
  echo "$t $v"
}
VJAR="$(mktemp)"; V2JAR="$(mktemp)"; RJAR="$(mktemp)"
read -r VTOKEN VID <<< "$(vendor "vendor8-$RUN@example.com" "Fulfil Mart $RUN" "$VJAR")"
read -r V2TOKEN V2ID <<< "$(vendor "vendor8b-$RUN@example.com" "Other Mart $RUN" "$V2JAR")"
claims "$VTOKEN" | grep -q "\"vendor_id\":\"$VID\"" && claims "$V2TOKEN" | grep -q "\"vendor_id\":\"$V2ID\"" && ok "two approved vendors" || bad "vendors" "$(claims "$VTOKEN")"
RICE=$(call POST /api/vendor/products "$VTOKEN" '{"name":"Fulfil Rice","categoryId":"'"$CAT"'","unitValue":1,"unitOfMeasure":"KG","foodType":"VEG"}' | body | json id)
call POST /api/vendor/products/$RICE/publish "$VTOKEN" >/dev/null
for _ in $(seq 1 60); do [ "$(call GET /api/vendor/inventory/$RICE "$VTOKEN" | cut -d' ' -f1)" = 200 ] && [ "$(call GET /api/vendor/prices/$RICE "$VTOKEN" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
call PUT /api/vendor/prices/$RICE "$VTOKEN" '{"mrp":120.00,"sellingPrice":100.00}' >/dev/null
call POST /api/vendor/inventory/$RICE/adjustments "$VTOKEN" '{"type":"SET","quantity":40,"reason":"STOCK_COUNT"}' >/dev/null
poll "the vendor's product is searchable and in stock" 15 '"inStock":true' GET /api/catalog/products/$RICE

RTOKEN=$(register "rest8-$RUN@example.com" RESTAURANT_OWNER "Fulfil Chef" "$RJAR")
for _ in $(seq 1 60); do REST=$(call GET /api/restaurants/me "$RTOKEN" | body | json id); [ -n "$REST" ] && break; sleep 0.25; done
HOURS=$(for d in MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY SUNDAY; do printf '{"dayOfWeek":"%s","opensAt":"00:00","closesAt":"23:59"},' $d; done)
call PUT /api/restaurants/me "$RTOKEN" '{"name":"Fulfil Kitchen '"$RUN"'","cuisines":["SOUTH_INDIAN"],"fssaiLicense":"12345678901234","contactEmail":"k@fulfil.in","contactPhone":"+919876543210","address":{"line1":"5 CMH Road","city":"Bengaluru","state":"Karnataka","postalCode":"560038","country":"IN","latitude":12.9784,"longitude":77.6408},"openingHours":['"${HOURS%,}"']}' >/dev/null
MCAT=$(call POST /api/restaurant/menu/categories "$RTOKEN" '{"name":"Dosa"}' | body | json id)
DOSA=$(call POST /api/restaurant/menu/items "$RTOKEN" '{"categoryId":"'"$MCAT"'","name":"Fulfil Dosa","price":120.00,"foodType":"VEG"}' | body | json id)
call POST /api/restaurants/me/submit "$RTOKEN" >/dev/null; call POST /api/admin/restaurants/$REST/approve "$ATOKEN" >/dev/null
for _ in $(seq 1 20); do RTOKEN=$(refresh "$RJAR"); claims "$RTOKEN" | grep -q "\"restaurant_id\":\"$REST\"" && break; sleep 0.5; done
claims "$RTOKEN" | grep -q "\"restaurant_id\":\"$REST\"" && ok "approved restaurant with a dish" || bad "restaurant" "$(claims "$RTOKEN")"

CTOKEN=$(register "cust8-$RUN@example.com" CUSTOMER "Fulfil Shopper")
for _ in $(seq 1 60); do [ "$(call GET /api/customers/me "$CTOKEN" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
ADDR=$(call POST /api/customers/me/addresses "$CTOKEN" '{"label":"Home","recipientName":"Fulfil Shopper","phone":"+919876543210","line1":"1 Test Street","landmark":"Near the park","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.975,"longitude":77.60}' | body | json id)
[ -n "$ADDR" ] && ok "customer with an address" || bad "customer" "no address"
open_stream vendor "$VTOKEN"; open_stream rest "$RTOKEN"; open_stream cust "$CTOKEN"

total() { call GET "/api/cart/preview?addressId=$ADDR" "$CTOKEN" | body | grep -o '"total":[0-9.]*' | tail -1 | cut -d: -f2; }
pay() { # orderId
  local b; b=$(call POST /api/payments/simulator/orders/$1/pay "$CTOKEN" '{"outcome":"SUCCESS"}' | body)
  call POST /api/payments/razorpay/verify "$CTOKEN" "{\"razorpayOrderId\":\"$(echo "$b" | json razorpayOrderId)\",\"razorpayPaymentId\":\"$(echo "$b" | json razorpayPaymentId)\",\"razorpaySignature\":\"$(echo "$b" | json razorpaySignature)\"}" >/dev/null
}
paid_order() { # key item-json -> orderId (placed, paid, CONFIRMED); the cart is emptied first
  local r o t
  call DELETE /api/cart "$CTOKEN" >/dev/null
  call POST /api/cart/items "$CTOKEN" "$2" >/dev/null
  t=$(total)
  for _ in 1 2 3; do
    r=$(call POST /api/orders "$CTOKEN" "{\"addressId\":\"$ADDR\",\"expectedTotal\":$t,\"note\":\"Ring twice\"}" "Idempotency-Key: $(key "$1")")
    case "${r%% *}" in 000|5*) sleep 1 ;; *) break ;; esac
  done
  o=$(echo "$r" | body | json id); [ -z "$o" ] && { echo "    (order $1 not placed: $(echo "$r" | cut -c1-200))" >&2; return; }
  for _ in $(seq 1 60); do [ "$(call GET /api/payments/orders/$o/checkout "$CTOKEN" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
  pay "$o"
  for _ in $(seq 1 60); do call GET /api/orders/$o "$CTOKEN" | grep -q '"status":"CONFIRMED"' && break; sleep 0.25; done
  echo "$o"
}
reserved() { call GET /api/vendor/inventory/$RICE "$VTOKEN" | body | num reserved; }
RICE2='{"type":"PRODUCT","productId":"'"$RICE"'","quantity":2}'
DOSA2='{"type":"MENU_ITEM","restaurantId":"'"$REST"'","menuItemId":"'"$DOSA"'","quantity":2,"replaceCart":true}'

echo "== Acceptance: a paid order appears on the store's board instantly"
O1=$(paid_order g1 "$RICE2"); ON1=$(call GET /api/orders/$O1 "$CTOKEN" | body | json orderNumber)
[ -n "$O1" ] && ok "grocery order $ON1 paid (CONFIRMED)" || bad "grocery order" "not placed"
poll "it is on the vendor's board as NEW" 5 "$(both "\"orderId\":\"$O1\"" '"status":"NEW"')" GET "/api/vendor/orders?status=NEW" "$VTOKEN"
await "vendor: NEW_ORDER alert" vendor 8 "$(both '"type":"NEW_ORDER"' "$ON1")"
await "vendor: STORE_ORDERS_CHANGED signal" vendor 5 "$(both '"type":"STORE_ORDERS_CHANGED"' "$O1")"
r=$(call GET /api/vendor/orders/$O1 "$VTOKEN")
echo "$r" | grep -q '"name":"Fulfil Shopper"' && echo "$r" | grep -q '"note":"Ring twice"' && echo "$r" | grep -q '"acceptBy":"' && echo "$r" | grep -q '"Fulfil Rice"'   && ok "the board card has the items, the customer's name, the note and the deadline" || bad "board card" "$(echo "$r" | cut -c1-400)"
echo "$r" | grep -q '"phone"\|"line1"' && bad "no phone or street address on the store's card" "$(echo "$r" | cut -c1-300)" || ok "no phone or street address on the store's card"
expect "another vendor cannot see it" 404 "$(call GET /api/vendor/orders/$O1 "$V2TOKEN")"
expect "another vendor cannot accept it" 404 "$(call POST /api/vendor/orders/$O1/accept "$V2TOKEN" '{}')"
expect "a customer cannot use the vendor board" 403 "$(call GET /api/vendor/orders "$CTOKEN")"

echo "== Accept → packing → ready; the customer can no longer cancel"
expect "vendor accepts (ready in 15 min)" 200 "$(call POST /api/vendor/orders/$O1/accept "$VTOKEN" '{"readyInMinutes":15}')"
expect "accepting twice is idempotent" 200 "$(call POST /api/vendor/orders/$O1/accept "$VTOKEN" '{"readyInMinutes":15}')"
expect "rejecting after accepting → 409" 409 "$(call POST /api/vendor/orders/$O1/reject "$VTOKEN" '{"reason":"TOO_BUSY"}')"
poll "order ACCEPTED with the ready time" 8 "$(both '"status":"ACCEPTED"' '"readyInMinutes":15')" GET /api/orders/$O1 "$CTOKEN"
await "customer: ORDER_ACCEPTED notification" cust 8 "$(both '"type":"ORDER_ACCEPTED"' "$ON1")"
has "the customer can no longer cancel (cancellable false)" '"cancellable":false' "$LAST"
expect "customer cancel after acceptance → 409" 409 "$(call POST /api/orders/$O1/cancel "$CTOKEN" '{}')"
expect "start packing" 200 "$(call POST /api/vendor/orders/$O1/preparing "$VTOKEN")"
poll "order PREPARING" 8 '"status":"PREPARING"' GET /api/orders/$O1 "$CTOKEN"
expect "ready for pickup" 200 "$(call POST /api/vendor/orders/$O1/ready "$VTOKEN")"
poll "order READY_FOR_PICKUP" 8 '"status":"READY_FOR_PICKUP"' GET /api/orders/$O1 "$CTOKEN"
await "customer: ORDER_READY notification" cust 8 "$(both '"type":"ORDER_READY"' "$ON1")"
[ "$(reserved)" = 2 ] && ok "its 2 units stay reserved until pickup (Phase 9)" || bad "reserved" "$(reserved)"
expect "preparing after ready → 409" 409 "$(call POST /api/vendor/orders/$O1/preparing "$VTOKEN")"

echo "== Acceptance: a store rejection refunds the customer and notifies them"
O2=$(paid_order r1 "$DOSA2"); ON2=$(call GET /api/orders/$O2 "$CTOKEN" | body | json orderNumber)
poll "the restaurant's board shows it" 5 "\"orderId\":\"$O2\"" GET "/api/restaurant/orders?status=NEW" "$RTOKEN"
await "restaurant: NEW_ORDER alert" rest 8 "$(both '"type":"NEW_ORDER"' "$ON2")"
expect "reject OTHER without a note → 400" 400 "$(call POST /api/restaurant/orders/$O2/reject "$RTOKEN" '{"reason":"OTHER"}')"
expect "restaurant rejects (too busy)" 200 "$(call POST /api/restaurant/orders/$O2/reject "$RTOKEN" '{"reason":"TOO_BUSY","note":"Kitchen is full"}')"
poll "order CANCELLED by the store" 8 "$(both '"status":"CANCELLED"' '"STORE_REJECTED"')" GET /api/orders/$O2 "$CTOKEN"
has "cancelled by STORE" '"by":"STORE"' "$LAST"
poll "the customer is refunded in full" 15 "$(both '"reason":"ORDER_CANCELLED"' '"status":"PROCESSED"')" GET /api/payments/orders/$O2 "$CTOKEN"
poll "the order shows REFUNDED" 10 '"status":"REFUNDED"' GET /api/orders/$O2 "$CTOKEN"
await "customer: told the store could not accept" cust 8 "$(both '"type":"ORDER_CANCELLED"' "could not accept.*$ON2|$ON2.*could not accept")"
O3=$(paid_order g2 "$RICE2"); ON3=$(call GET /api/orders/$O3 "$CTOKEN" | body | json orderNumber)
for _ in $(seq 1 20); do call GET /api/vendor/orders/$O3 "$VTOKEN" | grep -q '"status":"NEW"' && break; sleep 0.25; done
before=$(reserved)
expect "vendor rejects a grocery order (out of stock)" 200 "$(call POST /api/vendor/orders/$O3/reject "$VTOKEN" '{"reason":"OUT_OF_STOCK"}')"
poll "rejected grocery order CANCELLED" 8 '"status":"CANCELLED"' GET /api/orders/$O3 "$CTOKEN"
for _ in $(seq 1 40); do [ "$(reserved)" = $((before - 2)) ] && break; sleep 0.25; done
[ "$(reserved)" = $((before - 2)) ] && ok "its stock is released ($before → $(reserved) reserved)" || bad "stock release" "$before → $(reserved)"

echo "== Customer cancels before acceptance; admin cancels after acceptance"
O4=$(paid_order g3 "$RICE2")
for _ in $(seq 1 20); do call GET /api/vendor/orders/$O4 "$VTOKEN" | grep -q '"status":"NEW"' && break; sleep 0.25; done
expect "customer cancels a paid order the store has not accepted" 200 "$(call POST /api/orders/$O4/cancel "$CTOKEN" '{"reason":"Changed my mind"}')"
poll "it leaves the board (store order CANCELLED)" 8 '"status":"CANCELLED"' GET /api/vendor/orders/$O4 "$VTOKEN"
expect "accepting a cancelled order → 409" 409 "$(call POST /api/vendor/orders/$O4/accept "$VTOKEN" '{}')"
O5=$(paid_order g4 "$RICE2")
for _ in $(seq 1 20); do call GET /api/vendor/orders/$O5 "$VTOKEN" | grep -q '"status":"NEW"' && break; sleep 0.25; done
call POST /api/vendor/orders/$O5/accept "$VTOKEN" '{}' >/dev/null
poll "accepted" 8 '"status":"ACCEPTED"' GET /api/orders/$O5 "$CTOKEN"
has "admins may still cancel it (adminCancellable)" '"adminCancellable":true' "$(call GET /api/admin/orders/$O5 "$ATOKEN")"
expect "admin cancels the accepted order" 200 "$(call POST /api/admin/orders/$O5/cancel "$ATOKEN" '{"reason":"Customer called support"}')"
poll "refunded in full" 15 "$(both '"reason":"ORDER_CANCELLED"' '"status":"PROCESSED"')" GET /api/payments/orders/$O5 "$CTOKEN"
poll "store order CANCELLED" 8 '"status":"CANCELLED"' GET /api/vendor/orders/$O5 "$VTOKEN"

echo "== Acceptance: no answer before the deadline → auto-reject, refund, everyone told"
O6=$(paid_order g5 "$RICE2"); ON6=$(call GET /api/orders/$O6 "$CTOKEN" | body | json orderNumber)
for _ in $(seq 1 20); do r=$(call GET /api/vendor/orders/$O6 "$VTOKEN"); echo "$r" | grep -q '"status":"NEW"' && break; sleep 0.25; done
recv=$(echo "$r" | body | json receivedAt); by=$(echo "$r" | body | json acceptBy)
window=$(( $(date -d "$by" +%s 2>/dev/null || echo 0) - $(date -d "$recv" +%s 2>/dev/null || echo 0) ))
if [ "$window" -gt 0 ] && [ "$window" -le 120 ]; then
  poll "nobody accepts: auto-rejected (window ${window}s)" $((window + 20)) "$(both '"status":"CANCELLED"' '"STORE_NO_RESPONSE"')" GET /api/orders/$O6 "$CTOKEN"
  has "store order REJECTED automatically" "$(both '"status":"REJECTED"' '"auto":true')" "$(call GET /api/vendor/orders/$O6 "$VTOKEN")"
  poll "refunded" 15 "$(both '"reason":"ORDER_CANCELLED"' '"status":"PROCESSED"')" GET /api/payments/orders/$O6 "$CTOKEN"
  await "vendor: ORDER_AUTO_REJECTED" vendor 8 "$(both '"type":"ORDER_AUTO_REJECTED"' "$ON6")"
  await "customer: told the store did not respond" cust 8 "did not respond in time"
  AUTO=1
else
  skip "auto-reject" "acceptance window is ${window}s; start vendor-service with ACCEPTANCE_TIMEOUT=30s to test it"
  call POST /api/vendor/orders/$O6/reject "$VTOKEN" '{"reason":"TOO_BUSY"}' >/dev/null; AUTO=0
fi

echo "== Acceptance: the sales reports match the orders"
O7=$(paid_order r2 "$DOSA2")
for _ in $(seq 1 20); do call GET /api/restaurant/orders/$O7 "$RTOKEN" | grep -q '"status":"NEW"' && break; sleep 0.25; done
call POST /api/restaurant/orders/$O7/accept "$RTOKEN" '{"readyInMinutes":20}' >/dev/null
call POST /api/restaurant/orders/$O7/ready "$RTOKEN" >/dev/null
sleep 1
r=$(call GET /api/vendor/reports/sales "$VTOKEN"); b=$(echo "$r" | body)
# Vendor this run: O1 accepted+ready (2 × 100.00), O3 rejected, O4 cancelled before acceptance, O5 accepted then cancelled, O6 auto-rejected
check() { local label=$1 field=$2 want=$3 got; got=$(echo "$b" | num "$field"); [ "$got" = "$want" ] && ok "$label: $got" || bad "$label" "want $want, got $got · $(echo "$b" | cut -c1-400)"; }
check "vendor ordersReceived" ordersReceived 5
check "vendor ordersAccepted (incl. the one cancelled later)" ordersAccepted 2
check "vendor ordersRejected" ordersRejected 2
check "vendor autoRejected" autoRejected "$AUTO"
check "vendor ordersCancelled" ordersCancelled 2
check "vendor itemsSold" itemsSold 2
[ "$(echo "$b" | num grossSales | awk '{printf "%.2f", $1}')" = "200.00" ] && ok "vendor grossSales ₹200.00 (accepted, not cancelled)" || bad "grossSales" "$b"
[ "$(echo "$b" | num averageOrderValue | awk '{printf "%.2f", $1}')" = "200.00" ] && ok "vendor average order value ₹200.00" || bad "AOV" "$b"
has "top item is the rice, 2 units" "$(both '"name":"Fulfil Rice"' '"quantity":2')" "$b"
[ "$(echo "$b" | grep -o '"date":"[0-9-]*"' | wc -l)" = 7 ] && ok "the daily series covers the 7 days of the default range" || bad "daily series" "$b"
rr=$(call GET /api/restaurant/reports/sales "$RTOKEN" | body)
[ "$(echo "$rr" | num ordersReceived)" = 2 ] && [ "$(echo "$rr" | num ordersRejected)" = 1 ] && [ "$(echo "$rr" | num grossSales | awk '{printf "%.2f", $1}')" = "240.00" ] \
  && ok "restaurant report: 2 received, 1 rejected, ₹240.00 gross" || bad "restaurant report" "$rr"
expect "a report range over 92 days → 400" 400 "$(call GET "/api/vendor/reports/sales?from=2026-01-01&to=2026-11-01" "$VTOKEN")"

echo "== Menu item availability"
expect "mark the dish sold out" 200 "$(call PATCH /api/restaurant/menu/items/$DOSA/availability "$RTOKEN" '{"available":false}')"
call DELETE /api/cart "$CTOKEN" >/dev/null
r=$(call POST /api/cart/items "$CTOKEN" "$DOSA2"); [ "${r%% *}" != 200 ] && ok "a sold-out dish cannot be added (${r%% *})" || has "a sold-out dish is flagged in the cart" "ITEM_UNAVAILABLE" "$(call GET "/api/cart/preview?addressId=$ADDR" "$CTOKEN")"
call PATCH /api/restaurant/menu/items/$DOSA/availability "$RTOKEN" '{"available":true}' >/dev/null

echo "== Cleanup: cancel this run's orders that are still open (their deliveries would compete for other runs' partners)"
for o in ${O1:-} ${O2:-} ${O7:-}; do
  r=$(call POST /api/admin/orders/$o/cancel "$ATOKEN" '{"reason":"Smoke test cleanup"}'); cs=${r%% *}
  case "$cs" in 200|409) ;; *) bad "cleanup cancel $o" "$r";; esac
done
ok "open orders of this run cancelled"

echo "== Internal endpoints stay internal"
expect "/internal/** is not routed by the gateway" 404 "$(call GET /internal/vendors/$VID/store "$CTOKEN")"

rm -f "$VJAR" "$V2JAR" "$RJAR"
echo
echo "Passed: $PASS   Failed: $FAIL   Skipped: $SKIP"
[ "$FAIL" -eq 0 ]
