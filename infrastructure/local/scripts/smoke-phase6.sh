#!/usr/bin/env bash
# =====================================================================================
# Phase 6 end-to-end smoke test — orders & payments, everything through the API gateway.
# Prereq: cd infrastructure/local && docker compose --profile core --profile catalog --profile inventory \
#           --profile cart --profile orders up -d --build
# Payments: runs against the built-in payment SIMULATOR (no Razorpay keys in .env). With real Razorpay test keys the
# pay steps need a browser (Razorpay Checkout) and are skipped here.
# Payment timeout check: only runs when the stack was started with a short payment window, e.g.
#   ORDER_PAYMENT_WINDOW=45s PAYMENT_EXPIRY_POLL_INTERVAL=5s docker compose ... up -d order-service payment-service
# Run (Git Bash):  bash infrastructure/local/scripts/smoke-phase6.sh
# Exit code 0 = every check passed. Safe to re-run: every run uses its own users, store and names.
# =====================================================================================
set -uo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-admin@grocery.local}"
ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-Admin@12345}"
SIM_WEBHOOK_SECRET="${SIMULATOR_WEBHOOK_SECRET:-simulator_webhook_secret}"
RUN="$(date +%s)"
PASS=0; FAIL=0; SKIP=0
PAR="$(mktemp -d)"

ok()   { echo "  ✔ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ✘ $1"; echo "    → $2" | head -c 900; echo; FAIL=$((FAIL+1)); }
skip() { echo "  – $1 (skipped: $2)"; SKIP=$((SKIP+1)); }
json() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
num()  { grep -o "\"$1\":-\?[0-9.]*" | head -1 | cut -d: -f2; }
claims() { local p; p=$(echo "$1" | cut -d. -f2 | tr '_-' '/+'); while [ $(( ${#p} % 4 )) -ne 0 ]; do p="$p="; done; echo "$p" | base64 -d 2>/dev/null; }
call() {  # method path token [body] [extra header] [second extra header] -> "STATUS BODY"
  local method=$1 path=$2 token=${3:-} body=${4:-} header=${5:-} header2=${6:-}
  local out; out=$(mktemp)   # one file per call: calls run in parallel in the race checks
  local args=(-s -o "$out" -w '%{http_code}' -X "$method" "$GW$path" -H "X-Correlation-Id: smoke6-$RUN-$RANDOM")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  [ -n "$header" ] && args+=(-H "$header")
  [ -n "$header2" ] && args+=(-H "$header2")
  local status; status=$(curl "${args[@]}"); echo "$status $(cat "$out")"; rm -f "$out"
}
body() { cut -d' ' -f2-; }
expect() { local status=${3%% *}; if [ "$status" = "$2" ]; then ok "$1 ($status)"; else bad "$1 (expected $2, got $status)" "${3#* }"; fi; }
has() { if echo "$3" | grep -Eq -- "$2"; then ok "$1"; else bad "$1" "$3"; fi; }
poll() { # "label" maxSeconds "regex" method path [token]
  local label=$1 max=$2 pattern=$3 method=$4 path=$5 token=${6:-} start r
  start=$(date +%s%N)
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
hmac() { printf '%s' "$2" | openssl dgst -sha256 -hmac "$1" | awk '{print $NF}'; }
key() { echo "smoke6-$RUN-$1"; }
# place token addressId expectedTotal key -> "STATUS BODY"
place() { call POST /api/orders "$1" "{\"addressId\":\"$2\",\"expectedTotal\":$3}" "Idempotency-Key: $4"; }
total() { call GET "/api/cart/preview?addressId=$2" "$1" | body | grep -o '"total":[0-9.]*' | tail -1 | cut -d: -f2; }
# pay token orderId outcome -> verify response (SUCCESS) or simulator response (FAIL)
pay() {
  local r; r=$(call POST /api/payments/simulator/orders/$2/pay "$1" "{\"outcome\":\"$3\"}")
  if [ "$3" != "SUCCESS" ]; then echo "$r"; return; fi
  local b; b=$(echo "$r" | body)
  call POST /api/payments/razorpay/verify "$1" "{\"razorpayOrderId\":\"$(echo "$b" | json razorpayOrderId)\",\"razorpayPaymentId\":\"$(echo "$b" | json razorpayPaymentId)\",\"razorpaySignature\":\"$(echo "$b" | json razorpaySignature)\"}"
}
reserved() { call GET /api/vendor/inventory/$2 "$1" | body | num reserved; }

echo "== Health (public)"
for s in gateway order payment cart inventory pricing; do expect "health/$s" 200 "$(call GET /api/health/$s)"; done
ATOKEN=$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD"); [ -n "$ATOKEN" ] && ok "admin login" || bad "admin login" "no token"

echo "== Setup: an approved grocery store with priced, stocked products"
CAT=$(call POST /api/admin/categories "$ATOKEN" "{\"name\":\"Smoke Orders $RUN\"}" | body | json id)
VJAR="$(mktemp)"; VTOKEN=$(register "vendor6-$RUN@example.com" VENDOR "Order Grocer" "$VJAR")
poll "vendor draft created" 15 '^200 ' GET /api/vendors/me "$VTOKEN"; VENDOR_ID=$(echo "$LAST" | body | json id)
call PUT /api/vendors/me "$VTOKEN" '{"businessName":"Order Mart '"$RUN"'","legalName":"Order Mart Pvt Ltd","gstin":"29ABCDE1234F1Z5","fssaiLicense":"12345678901234","contactEmail":"ops@order.in","contactPhone":"+919876543210","address":{"line1":"4 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.97,"longitude":77.59}}' >/dev/null
call POST /api/vendors/me/submit "$VTOKEN" >/dev/null
call POST /api/admin/vendors/$VENDOR_ID/approve "$ATOKEN" >/dev/null
for _ in $(seq 1 15); do VTOKEN=$(refresh "$VJAR"); claims "$VTOKEN" | grep -q "\"vendor_id\":\"$VENDOR_ID\"" && break; sleep 1; done
claims "$VTOKEN" | grep -q "\"vendor_id\":\"$VENDOR_ID\"" && ok "vendor approved" || bad "vendor_id claim" "$(claims "$VTOKEN")"
RICE=$(call POST /api/vendor/products "$VTOKEN" '{"name":"Sona Masoori Rice","categoryId":"'"$CAT"'","unitValue":1,"unitOfMeasure":"KG","foodType":"VEG"}' | body | json id)
DAL=$(call POST /api/vendor/products "$VTOKEN" '{"name":"Toor Dal","categoryId":"'"$CAT"'","unitValue":1,"unitOfMeasure":"KG","foodType":"VEG"}' | body | json id)
call POST /api/vendor/products/$RICE/publish "$VTOKEN" >/dev/null; call POST /api/vendor/products/$DAL/publish "$VTOKEN" >/dev/null
poll "pricing knows the products" 15 '^200 ' GET /api/vendor/prices/$DAL "$VTOKEN"
poll "inventory knows the products" 15 '^200 ' GET /api/vendor/inventory/$DAL "$VTOKEN"
call PUT /api/vendor/prices/$RICE "$VTOKEN" '{"mrp":120.00,"sellingPrice":100.00}' >/dev/null
call PUT /api/vendor/prices/$DAL "$VTOKEN" '{"mrp":180.00,"sellingPrice":150.00}' >/dev/null
call POST /api/vendor/inventory/$RICE/adjustments "$VTOKEN" '{"type":"SET","quantity":3,"reason":"STOCK_COUNT"}' >/dev/null
call POST /api/vendor/inventory/$DAL/adjustments "$VTOKEN" '{"type":"SET","quantity":50,"reason":"STOCK_COUNT"}' >/dev/null
poll "products searchable with price and stock" 15 '"inStock":true' GET /api/catalog/products/$RICE

echo "== Setup: an approved restaurant (open all day) and customers"
RJAR="$(mktemp)"; RTOKEN=$(register "rest6-$RUN@example.com" RESTAURANT_OWNER "Order Chef" "$RJAR")
poll "restaurant draft created" 15 '^200 ' GET /api/restaurants/me "$RTOKEN"; REST_ID=$(echo "$LAST" | body | json id)
HOURS=$(for d in MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY SUNDAY; do printf '{"dayOfWeek":"%s","opensAt":"00:00","closesAt":"23:59"},' $d; done)
call PUT /api/restaurants/me "$RTOKEN" '{"name":"Order Kitchen '"$RUN"'","cuisines":["SOUTH_INDIAN"],"fssaiLicense":"12345678901234","contactEmail":"k@order.in","contactPhone":"+919876543210","address":{"line1":"5 CMH Road","city":"Bengaluru","state":"Karnataka","postalCode":"560038","country":"IN","latitude":12.9784,"longitude":77.6408},"openingHours":['"${HOURS%,}"']}' >/dev/null
MCAT=$(call POST /api/restaurant/menu/categories "$RTOKEN" '{"name":"Dosa"}' | body | json id)
DOSA=$(call POST /api/restaurant/menu/items "$RTOKEN" '{"categoryId":"'"$MCAT"'","name":"Ghee Roast Dosa","price":120.00,"foodType":"VEG"}' | body | json id)
call POST /api/restaurants/me/submit "$RTOKEN" >/dev/null
call POST /api/admin/restaurants/$REST_ID/approve "$ATOKEN" >/dev/null
customer() { # email name -> "token addressId"
  local t a; t=$(register "$1" CUSTOMER "$2")
  for _ in $(seq 1 60); do [ "$(call GET /api/customers/me "$t" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
  a=$(call POST /api/customers/me/addresses "$t" '{"label":"Home","recipientName":"'"$2"'","phone":"+919876543210","line1":"1 Test Street","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.975,"longitude":77.60}' | body | json id)
  echo "$t $a"
}
read -r CTOKEN ADDR <<< "$(customer "cust6-$RUN@example.com" "Order Shopper")"
read -r C2TOKEN ADDR2 <<< "$(customer "cust6b-$RUN@example.com" "Second Shopper")"
[ -n "$ADDR" ] && [ -n "$ADDR2" ] && ok "two customers with a delivery address" || bad "customers" "$ADDR $ADDR2"

echo "== Placing an order: Idempotency-Key, live checks"
expect "no Idempotency-Key → 400" 400 "$(call POST /api/orders "$CTOKEN" '{"expectedTotal":1}')"
has "empty cart → CHECKOUT_BLOCKED" '"errorCode":"CHECKOUT_BLOCKED"' "$(place "$CTOKEN" "$ADDR" 1 "$(key empty)")"
call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$RICE"'","quantity":2}' >/dev/null
call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$DAL"'","quantity":1}' >/dev/null
T1=$(total "$CTOKEN" "$ADDR"); [ -n "$T1" ] && ok "preview total ₹$T1" || bad "preview total" "empty"
r=$(place "$CTOKEN" "$ADDR" 1.00 "$(key wrong-total)")
has "stale expectedTotal → TOTAL_CHANGED with the current total" "\"errorCode\":\"TOTAL_CHANGED\".*\"currentTotal\":$T1|\"currentTotal\":$T1.*\"errorCode\":\"TOTAL_CHANGED\"" "$r"
K1=$(key grocery-1)
rm -f "$PAR"/*
for i in 1 2 3 4 5; do curl -s -D "$PAR/h$i" -o "$PAR/b$i" -w '%{http_code}\n' -X POST "$GW/api/orders" -H "Authorization: Bearer $CTOKEN" \
  -H "Idempotency-Key: $K1" -H 'Content-Type: application/json' -d "{\"addressId\":\"$ADDR\",\"expectedTotal\":$T1}" > "$PAR/s$i" & done; wait
ids=$(for i in 1 2 3 4 5; do json id < "$PAR/b$i"; done | sort -u); statuses=$(cat "$PAR"/s* | sort | uniq -c | tr -s ' ' | tr '\n' ';')
O1=$(echo "$ids" | head -1)
[ "$(echo "$ids" | wc -l)" = 1 ] && [ -n "$O1" ] && ! cat "$PAR"/s* | grep -qv '^201$' \
  && ok "5 parallel requests with one key → one order, all 201 ($statuses)" || bad "parallel idempotent create" "$statuses ids=$ids"
grep -qi '^Idempotent-Replayed: true' "$PAR"/h* && ok "replays are marked Idempotent-Replayed" || bad "Idempotent-Replayed header" "$(cat "$PAR"/h1)"
has "the customer has exactly one order" '"totalElements":1' "$(call GET /api/orders "$CTOKEN")"
expect "same key, different body → 422" 422 "$(place "$CTOKEN" "$ADDR" 999.00 "$K1")"
ON1=$(call GET /api/orders/$O1 "$CTOKEN" | body | json orderNumber)

echo "== Grocery saga: stock reserved → awaiting payment; the cart is emptied"
poll "order AWAITING_PAYMENT after the stock is reserved" 15 '"status":"AWAITING_PAYMENT"' GET /api/orders/$O1 "$CTOKEN"
[ "$(reserved "$VTOKEN" "$RICE")" = 2 ] && ok "inventory holds 2 rice for the order" || bad "rice reserved" "$(call GET /api/vendor/inventory/$RICE "$VTOKEN")"
poll "cart emptied by OrderCreated" 10 '"store":null' GET /api/cart "$CTOKEN"
expect "another customer cannot see the order" 404 "$(call GET /api/orders/$O1 "$C2TOKEN")"

echo "== Payment: decline, retry, pay, verify"
poll "checkout session ready" 15 '^200 ' GET /api/payments/orders/$O1/checkout "$CTOKEN"
PROVIDER=$(echo "$LAST" | body | json provider); RZP_ORDER=$(echo "$LAST" | body | json razorpayOrderId)
has "amount in paise = total × 100" "\"amount\":$(awk "BEGIN{printf \"%d\", $T1*100}")" "$LAST"
expect "another customer gets no checkout session" 404 "$(call GET /api/payments/orders/$O1/checkout "$C2TOKEN")"
if [ "$PROVIDER" != "SIMULATOR" ]; then
  skip "simulator payment steps" "payment-service runs against real Razorpay (provider $PROVIDER): pay in the browser"
else
  expect "simulated decline" 200 "$(pay "$CTOKEN" "$O1" FAIL)"
  poll "decline recorded, order still payable" 10 '"lastFailure":\{"code"' GET /api/orders/$O1 "$CTOKEN"
  has "still AWAITING_PAYMENT after a decline" '"status":"AWAITING_PAYMENT"' "$LAST"
  expect "verify with a forged signature → 400" 400 "$(call POST /api/payments/razorpay/verify "$CTOKEN" '{"razorpayOrderId":"'"$RZP_ORDER"'","razorpayPaymentId":"pay_forged","razorpaySignature":"00"}')"
  r=$(pay "$CTOKEN" "$O1" SUCCESS); expect "retry succeeds: pay + verify" 200 "$r"
  has "payment CAPTURED" '"status":"CAPTURED"' "$r"
  PAY1=$(echo "$r" | body | json id)
  poll "order CONFIRMED and PAID" 10 '"status":"CONFIRMED".*"status":"PAID"|"status":"PAID".*"status":"CONFIRMED"' GET /api/orders/$O1 "$CTOKEN"
  [ "$(reserved "$VTOKEN" "$RICE")" = 2 ] && ok "confirmed order keeps its stock reserved" || bad "rice still reserved" "$(call GET /api/vendor/inventory/$RICE "$VTOKEN")"
  expect "checkout again → already captured" 409 "$(call GET /api/payments/orders/$O1/checkout "$CTOKEN")"

  echo "== Webhooks: signature and duplicates"
  expect "webhook with a bad signature → 400" 400 "$(call POST /api/payments/razorpay/webhook "" '{"event":"payment.captured"}' "X-Razorpay-Signature: 00")"
  # A second order to receive a declined-payment webhook twice
  call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$DAL"'","quantity":1}' >/dev/null
  T2=$(total "$CTOKEN" "$ADDR"); r=$(place "$CTOKEN" "$ADDR" "$T2" "$(key grocery-2)"); expect "second order" 201 "$r"; O2=$(echo "$r" | body | json id)
  poll "second order payable" 15 '^200 ' GET /api/payments/orders/$O2/checkout "$CTOKEN"; RZP2=$(echo "$LAST" | body | json razorpayOrderId)
  WH='{"entity":"event","event":"payment.failed","contains":["payment"],"payload":{"payment":{"entity":{"id":"pay_smoke'"$RUN"'","entity":"payment","amount":100,"currency":"INR","status":"failed","order_id":"'"$RZP2"'","method":"card","error_code":"BAD_REQUEST_ERROR","error_description":"Smoke test decline"}}},"created_at":'"$RUN"'}'
  SIG=$(hmac "$SIM_WEBHOOK_SECRET" "$WH")
  for n in 1 2 3; do expect "payment.failed webhook delivery #$n (same event id)" 200 \
    "$(call POST /api/payments/razorpay/webhook "" "$WH" "X-Razorpay-Signature: $SIG" "X-Razorpay-Event-Id: evt_smoke$RUN")"; done
  n=$(call GET /api/payments/orders/$O2 "$CTOKEN" | body | grep -o '"razorpayPaymentId":"pay_smoke'"$RUN"'"' | wc -l)
  [ "$n" = 1 ] && ok "the duplicated webhook was applied once (1 failed attempt)" || bad "duplicate webhook applied $n times" "$(call GET /api/payments/orders/$O2 "$CTOKEN")"

  echo "== Cancellation: unpaid, paid (refund), late capture (refund)"
  r=$(call POST /api/orders/$O2/cancel "$CTOKEN" '{"reason":"Changed my mind"}'); expect "cancel the unpaid order" 200 "$r"
  has "CANCELLED by the customer" '"status":"CANCELLED".*"CUSTOMER_CANCELLED"' "$r"
  expect "cancelling twice is idempotent" 200 "$(call POST /api/orders/$O2/cancel "$CTOKEN" '{}')"
  poll "payment session cancelled" 10 '"status":"CANCELLED"' GET /api/payments/orders/$O2 "$CTOKEN"
  r=$(pay "$CTOKEN" "$O2" SUCCESS); expect "the customer still pays in an old tab (late capture)" 200 "$r"
  poll "late capture refunded automatically" 15 '"reason":"LATE_CAPTURE".*"status":"PROCESSED"' GET /api/payments/orders/$O2 "$CTOKEN"
  poll "order shows the refund" 10 '"status":"REFUNDED"' GET /api/orders/$O2 "$CTOKEN"
  r=$(call POST /api/orders/$O1/cancel "$CTOKEN" '{"reason":"Need it earlier"}'); expect "cancel the paid order (before the store accepts)" 200 "$r"
  poll "paid order refunded in full" 15 '"reason":"ORDER_CANCELLED".*"status":"PROCESSED"' GET /api/payments/orders/$O1 "$CTOKEN"
  poll "stock released when the order is cancelled" 10 '"reserved":0' GET /api/vendor/inventory/$RICE "$VTOKEN"
fi

echo "== Acceptance: two customers race for the last units — no overselling"
call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$RICE"'","quantity":2}' >/dev/null
call POST /api/cart/items "$C2TOKEN" '{"type":"PRODUCT","productId":"'"$RICE"'","quantity":2}' >/dev/null
TA=$(total "$CTOKEN" "$ADDR"); TB=$(total "$C2TOKEN" "$ADDR2")
place "$CTOKEN" "$ADDR" "$TA" "$(key race-a)" > "$PAR/ra" & place "$C2TOKEN" "$ADDR2" "$TB" "$(key race-b)" > "$PAR/rb" & wait
OA=$(body < "$PAR/ra" | json id); OB=$(body < "$PAR/rb" | json id)
sleep 3
SA=$( [ -n "$OA" ] && call GET /api/orders/$OA "$CTOKEN" | body | json status ); SB=$( [ -n "$OB" ] && call GET /api/orders/$OB "$C2TOKEN" | body | json status )
R=$(reserved "$VTOKEN" "$RICE")
echo "    order A: ${SA:-$(cut -c1-60 "$PAR/ra")} · order B: ${SB:-$(cut -c1-60 "$PAR/rb")} · rice reserved: $R of 3"
[ "$R" -le 3 ] && [ "$R" = 2 ] && ok "only one order holds the stock (reserved 2 of 3)" || bad "oversold or nothing reserved" "reserved=$R"
outcomes="$SA $SB"
echo "$outcomes" | grep -q AWAITING_PAYMENT && { echo "$outcomes" | grep -q REJECTED || grep -q CHECKOUT_BLOCKED "$PAR/ra" "$PAR/rb"; } \
  && ok "the other order is REJECTED (OUT_OF_STOCK) or was blocked at checkout" || bad "race outcome" "$outcomes $(cat "$PAR/ra" "$PAR/rb")"
for o in "$OA:$CTOKEN" "$OB:$C2TOKEN"; do id=${o%%:*}; t=${o#*:}; [ -n "$id" ] && \
  echo "$(call GET /api/orders/$id "$t")" | grep -q '"status":"REJECTED"' && has "rejection lists the missing item" '"reason":"OUT_OF_STOCK".*"unavailableItems":\[\{' "$(call GET /api/orders/$id "$t")"; done
for o in "$OA:$CTOKEN" "$OB:$C2TOKEN"; do id=${o%%:*}; t=${o#*:}; [ -n "$id" ] && call POST /api/orders/$id/cancel "$t" '{}' >/dev/null; done

echo "== Restaurant order: straight to payment"
call POST /api/cart/items "$C2TOKEN" '{"type":"MENU_ITEM","restaurantId":"'"$REST_ID"'","menuItemId":"'"$DOSA"'","quantity":2,"replaceCart":true}' >/dev/null
T3=$(total "$C2TOKEN" "$ADDR2"); r=$(place "$C2TOKEN" "$ADDR2" "$T3" "$(key restaurant)"); expect "restaurant order" 201 "$r"
O3=$(echo "$r" | body | json id); ON3=$(echo "$r" | body | json orderNumber)
has "created AWAITING_PAYMENT (no stock step)" '"type":"RESTAURANT".*"status":"AWAITING_PAYMENT"|"status":"AWAITING_PAYMENT".*"type":"RESTAURANT"' "$r"
if [ "$PROVIDER" = "SIMULATOR" ]; then
  poll "restaurant checkout session" 15 '^200 ' GET /api/payments/orders/$O3/checkout "$C2TOKEN"
  expect "pay the restaurant order" 200 "$(pay "$C2TOKEN" "$O3" SUCCESS)"
  poll "restaurant order CONFIRMED" 10 '"status":"CONFIRMED"' GET /api/orders/$O3 "$C2TOKEN"
fi

echo "== Admin: monitor, stats, refunds"
expect "customer cannot use the admin order monitor" 403 "$(call GET /api/admin/orders "$CTOKEN")"
has "admin finds the order by number" "\"id\":\"$O3\"" "$(call GET "/api/admin/orders?q=$ON3" "$ATOKEN")"
has "admin order detail has the customer and timeline" '"customerId":".*"timeline":\[' "$(call GET /api/admin/orders/$O3 "$ATOKEN")"
has "today's stats" '"orders":[1-9][0-9]*.*"byStatus"' "$(call GET /api/admin/stats "$ATOKEN")"
expect "admin payment monitor" 200 "$(call GET "/api/admin/payments?orderId=$O3" "$ATOKEN")"
if [ "$PROVIDER" = "SIMULATOR" ]; then
  RK=$(key refund-1)
  r=$(call POST /api/admin/refunds "$ATOKEN" '{"orderId":"'"$O3"'","amount":50.00,"note":"Dosa was cold"}' "Idempotency-Key: $RK"); expect "partial refund ₹50" 201 "$r"
  RF=$(echo "$r" | body | json id)
  has "retrying the refund returns the same refund" "\"id\":\"$RF\"" "$(call POST /api/admin/refunds "$ATOKEN" '{"orderId":"'"$O3"'","amount":50.00,"note":"Dosa was cold"}' "Idempotency-Key: $RK")"
  expect "refund above what is left → 422" 422 "$(call POST /api/admin/refunds "$ATOKEN" '{"orderId":"'"$O3"'","amount":100000.00,"note":"Too much"}' "Idempotency-Key: $(key refund-2)")"
  expect "a customer cannot refund" 403 "$(call POST /api/admin/refunds "$C2TOKEN" '{"orderId":"'"$O3"'","note":"x"}' "Idempotency-Key: $(key refund-3)")"
  poll "order shows the partial refund, still CONFIRMED" 10 '"status":"CONFIRMED".*"refundedAmount":50|"refundedAmount":50.*"status":"CONFIRMED"' GET /api/orders/$O3 "$C2TOKEN"
fi

echo "== Acceptance: a payment timeout cancels the order and releases stock"
call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$DAL"'","quantity":2,"replaceCart":true}' >/dev/null
T4=$(total "$CTOKEN" "$ADDR"); r=$(place "$CTOKEN" "$ADDR" "$T4" "$(key timeout)"); O4=$(echo "$r" | body | json id)
created=$(echo "$r" | body | json createdAt); due=$(echo "$r" | body | grep -o '"dueAt":"[^"]*"' | head -1 | cut -d'"' -f4)
window=$(( $(date -d "$due" +%s 2>/dev/null || echo 0) - $(date -d "$created" +%s 2>/dev/null || echo 0) ))
if [ "$window" -gt 0 ] && [ "$window" -le 120 ]; then
  poll "order awaiting payment (window ${window}s)" 15 '"status":"AWAITING_PAYMENT"' GET /api/orders/$O4 "$CTOKEN"
  poll "unpaid order CANCELLED (PAYMENT_TIMEOUT)" $((window + 60)) '"status":"CANCELLED".*"PAYMENT_TIMEOUT"' GET /api/orders/$O4 "$CTOKEN"
  poll "its stock is released" 15 '"reserved":0' GET /api/vendor/inventory/$DAL "$VTOKEN"
else
  skip "payment timeout" "payment window is ${window}s; start order-service with ORDER_PAYMENT_WINDOW=45s to test it"
  call POST /api/orders/$O4/cancel "$CTOKEN" '{}' >/dev/null
fi

echo "== Internal endpoints stay internal"
expect "/internal/** is not routed by the gateway" 404 "$(call GET /internal/vendors/$VENDOR_ID/store "$CTOKEN")"

rm -rf "$VJAR" "$RJAR" "$PAR"
echo
echo "Passed: $PASS   Failed: $FAIL   Skipped: $SKIP"
[ "$FAIL" -eq 0 ]
