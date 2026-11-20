#!/usr/bin/env bash
# =====================================================================================
# Phase 9 end-to-end smoke test — delivery, everything through the API gateway.
# Prereq (short offer TTL so the "expired offer → next partner" check is quick; payments use the simulator):
#   cd infrastructure/local && DELIVERY_OFFER_TTL=10s DELIVERY_POLL_INTERVAL=2s docker compose --profile core \
#     --profile catalog --profile inventory --profile cart --profile orders --profile realtime up -d --build
# Four partners stand ~0.5, ~1.2, ~2.5 and ~4.5 km from the store (search radii 3, 6, 10 km; 2 offers per wave).
# Run (Git Bash):  bash infrastructure/local/scripts/smoke-phase9.sh
# Exit code 0 = every check passed. Safe to re-run: every run uses its own users, store and names.
# =====================================================================================
set -uo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-admin@grocery.local}"
ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-Admin@12345}"
RUN="$(date +%s)"
PASS=0; FAIL=0; SKIP=0
PAR="$(mktemp -d)"
declare -A STREAM_PID=()
HEARTBEAT_PID=""

ok()   { echo "  ✔ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ✘ $1"; echo "    → $2" | head -c 900; echo; FAIL=$((FAIL+1)); }
skip() { echo "  – $1 (skipped: $2)"; SKIP=$((SKIP+1)); }
json() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
num()  { grep -o "\"$1\":-\?[0-9.]*" | head -1 | cut -d: -f2; }
claims() { local p; p=$(echo "$1" | cut -d. -f2 | tr '_-' '/+'); while [ $(( ${#p} % 4 )) -ne 0 ]; do p="$p="; done; echo "$p" | base64 -d 2>/dev/null; }
call() {  # method path token [body] [extra header] -> "STATUS BODY"
  local method=$1 path=$2 token=${3:-} body=${4:-} header=${5:-}
  local out; out=$(mktemp)
  local args=(-s -o "$out" -w '%{http_code}' -X "$method" "$GW$path" -H "X-Correlation-Id: smoke9-$RUN-$RANDOM")
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
key() { echo "smoke9-$RUN-$1"; }
open_stream() { : > "$PAR/$1.sse"; curl -sN --max-time 900 -H "Authorization: Bearer $2" -H 'Accept: text/event-stream' "$GW/api/notifications/stream" > "$PAR/$1.sse" 2>/dev/null & STREAM_PID[$1]=$!; }
await() { # "label" name maxSeconds regex
  local label=$1 name=$2 max=$3 pattern=$4 start; start=$(date +%s%N)
  for _ in $(seq 1 $((max * 10))); do
    if grep -Eq -- "$pattern" "$PAR/$name.sse"; then ok "$label ($(( ($(date +%s%N) - start) / 1000000 )) ms)"; return 0; fi
    sleep 0.1
  done
  bad "$label" "$(tail -c 500 "$PAR/$name.sse")"; return 1
}
cleanup() { [ -n "$HEARTBEAT_PID" ] && kill "$HEARTBEAT_PID" 2>/dev/null; for n in "${!STREAM_PID[@]}"; do kill "${STREAM_PID[$n]}" 2>/dev/null; done; rm -rf "$PAR"; }
trap cleanup EXIT

# Partners: positions live in files so the heartbeat (keeps locations fresh) and the "driving" step agree.
setpos() { echo "$2 $3" > "$PAR/pos_$1"; }
report() { # name -> PUT location from the position file
  local t; t=$(cat "$PAR/tok_$1"); read -r la lo < "$PAR/pos_$1"
  call PUT /api/delivery/partners/me/location "$t" "{\"latitude\":$la,\"longitude\":$lo,\"accuracyMeters\":8}"
}
heartbeat() { while true; do for f in "$PAR"/online_*; do [ -e "$f" ] || continue; report "${f##*/online_}" >/dev/null; done; sleep 8; done; }
online() { local t; t=$(cat "$PAR/tok_$1"); call PUT /api/delivery/partners/me/availability "$t" '{"online":true}' >/dev/null; touch "$PAR/online_$1"; report "$1" >/dev/null; }
offline() { local t; t=$(cat "$PAR/tok_$1"); rm -f "$PAR/online_$1"; call PUT /api/delivery/partners/me/availability "$t" '{"online":false}'; }
offers() { call GET /api/delivery/offers "$(cat "$PAR/tok_$1")"; }

echo "== Preflight: every Debezium connector task is running (otherwise no events flow)"
if bash "$(dirname "$0")/check-connectors.sh" > "$PAR/connectors" 2>&1; then ok "all connector tasks RUNNING"; else bad "connector tasks" "$(cat "$PAR/connectors")"; echo "Aborting: fix the connectors first (see README → Troubleshooting)."; exit 1; fi

echo "== Health"
for s in gateway delivery order inventory notification; do expect "health/$s" 200 "$(call GET /api/health/$s)"; done
ATOKEN=$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD"); [ -n "$ATOKEN" ] && ok "admin login" || bad "admin login" "no token"

echo "== Setup: a vendor at MG Road (12.97, 77.59), a customer ~1.2 km away, four approved partners"
VJAR="$(mktemp)"
VTOKEN=$(register "vendor9-$RUN@example.com" VENDOR "Delivery Mart" "$VJAR")
for _ in $(seq 1 60); do VID=$(call GET /api/vendors/me "$VTOKEN" | body | json id); [ -n "$VID" ] && break; sleep 0.25; done
call PUT /api/vendors/me "$VTOKEN" '{"businessName":"Delivery Mart '"$RUN"'","legalName":"Delivery Mart Pvt Ltd","gstin":"29ABCDE1234F1Z5","fssaiLicense":"12345678901234","contactEmail":"ops@deliver.in","contactPhone":"+919876543210","address":{"line1":"4 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.97,"longitude":77.59}}' >/dev/null
call POST /api/vendors/me/submit "$VTOKEN" >/dev/null; call POST /api/admin/vendors/$VID/approve "$ATOKEN" >/dev/null
for _ in $(seq 1 20); do VTOKEN=$(refresh "$VJAR"); claims "$VTOKEN" | grep -q "\"vendor_id\":\"$VID\"" && break; sleep 0.5; done
CAT=$(call POST /api/admin/categories "$ATOKEN" "{\"name\":\"Smoke Delivery $RUN\"}" | body | json id)
RICE=$(call POST /api/vendor/products "$VTOKEN" '{"name":"Delivery Rice","categoryId":"'"$CAT"'","unitValue":1,"unitOfMeasure":"KG","foodType":"VEG"}' | body | json id)
call POST /api/vendor/products/$RICE/publish "$VTOKEN" >/dev/null
for _ in $(seq 1 60); do [ "$(call GET /api/vendor/inventory/$RICE "$VTOKEN" | cut -d' ' -f1)" = 200 ] && [ "$(call GET /api/vendor/prices/$RICE "$VTOKEN" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
call PUT /api/vendor/prices/$RICE "$VTOKEN" '{"mrp":120.00,"sellingPrice":100.00}' >/dev/null
call POST /api/vendor/inventory/$RICE/adjustments "$VTOKEN" '{"type":"SET","quantity":30,"reason":"STOCK_COUNT"}' >/dev/null
poll "the product is in stock" 15 '"inStock":true' GET /api/catalog/products/$RICE
CTOKEN=$(register "cust9-$RUN@example.com" CUSTOMER "Tracking Shopper")
for _ in $(seq 1 60); do [ "$(call GET /api/customers/me "$CTOKEN" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
ADDR=$(call POST /api/customers/me/addresses "$CTOKEN" '{"label":"Home","recipientName":"Tracking Shopper","phone":"+919811122233","line1":"9 Residency Road","landmark":"Opposite the park","city":"Bengaluru","state":"Karnataka","postalCode":"560025","country":"IN","latitude":12.975,"longitude":77.60}' | body | json id)
C2TOKEN=$(register "cust9b-$RUN@example.com" CUSTOMER "Other Shopper")
partner() { # name lat lng
  local t id; t=$(register "rider9$1-$RUN@example.com" DELIVERY_PARTNER "Rider $1")
  for _ in $(seq 1 60); do id=$(call GET /api/delivery/partners/me "$t" | body | json id); [ -n "$id" ] && break; sleep 0.25; done
  call PUT /api/delivery/partners/me "$t" '{"fullName":"Rider '"$1"'","phone":"+919800000000","city":"Bengaluru","vehicleType":"BICYCLE"}' >/dev/null
  call POST /api/delivery/partners/me/submit "$t" >/dev/null
  call POST /api/admin/delivery-partners/$id/approve "$ATOKEN" >/dev/null
  echo "$t" > "$PAR/tok_$1"; echo "$id" > "$PAR/id_$1"; setpos "$1" "$2" "$3"
}
partner P1 12.9745 77.59      # ~0.5 km north of the store
partner P2 12.97 77.601       # ~1.2 km east
partner P3 12.9475 77.59      # ~2.5 km south
partner P4 13.0105 77.59      # ~4.5 km north (outside the first 3 km radius)
for p in P1 P2 P3 P4; do [ -s "$PAR/id_$p" ] || bad "partner $p" "not created"; done
P5T=$(register "rider9P5-$RUN@example.com" DELIVERY_PARTNER "Rider P5")
for _ in $(seq 1 60); do [ "$(call GET /api/delivery/partners/me "$P5T" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
r=$(call PUT /api/delivery/partners/me/availability "$P5T" '{"online":true}')
expect "a partner who is not approved cannot go online" 403 "$r"; has "… with PARTNER_NOT_ACTIVE" '"errorCode":"PARTNER_NOT_ACTIVE"' "$r"
expect "a customer cannot go online as a partner" 403 "$(call PUT /api/delivery/partners/me/availability "$CTOKEN" '{"online":true}')"
for p in P1 P2 P3 P4; do online $p; done
heartbeat & HEARTBEAT_PID=$!
r=$(call GET /api/delivery/partners/me/status "$(cat "$PAR/tok_P1")"); has "partner P1 online with a location" "$(both '"online":true' '"latitude":12.97')" "$r"
[ -n "$ADDR" ] && ok "vendor, product, customer and 4 online partners ready" || bad "setup" "no address"
for p in P1 P2 P3 P4; do open_stream $p "$(cat "$PAR/tok_$p")"; done
open_stream cust "$CTOKEN"; open_stream admin "$ATOKEN"

total() { call GET "/api/cart/preview?addressId=$ADDR" "$CTOKEN" | body | grep -o '"total":[0-9.]*' | tail -1 | cut -d: -f2; }
accepted_order() { # key -> orderId (placed, paid, accepted by the vendor)
  local r o b
  call DELETE /api/cart "$CTOKEN" >/dev/null
  call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$RICE"'","quantity":2}' >/dev/null
  r=$(call POST /api/orders "$CTOKEN" "{\"addressId\":\"$ADDR\",\"expectedTotal\":$(total),\"note\":\"Call on arrival\"}" "Idempotency-Key: $(key "$1")")
  o=$(echo "$r" | body | json id); [ -z "$o" ] && { echo "    (order $1 not placed: $(echo "$r" | cut -c1-200))" >&2; return; }
  for _ in $(seq 1 60); do [ "$(call GET /api/payments/orders/$o/checkout "$CTOKEN" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
  b=$(call POST /api/payments/simulator/orders/$o/pay "$CTOKEN" '{"outcome":"SUCCESS"}' | body)
  call POST /api/payments/razorpay/verify "$CTOKEN" "{\"razorpayOrderId\":\"$(echo "$b" | json razorpayOrderId)\",\"razorpayPaymentId\":\"$(echo "$b" | json razorpayPaymentId)\",\"razorpaySignature\":\"$(echo "$b" | json razorpaySignature)\"}" >/dev/null
  for _ in $(seq 1 60); do call GET /api/vendor/orders/$o "$VTOKEN" | grep -q '"status":"NEW"' && break; sleep 0.25; done
  call POST /api/vendor/orders/$o/accept "$VTOKEN" '{"readyInMinutes":15}' >/dev/null
  echo "$o"
}

echo "== Acceptance: the nearest partners get the offer"
O1=$(accepted_order o1); ON1=$(call GET /api/orders/$O1 "$CTOKEN" | body | json orderNumber)
[ -n "$O1" ] && ok "order $ON1 paid and accepted by the vendor" || bad "order 1" "not placed"
poll "P1 (~0.5 km) has an offer" 10 '"offerId"' GET /api/delivery/offers "$(cat "$PAR/tok_P1")"; R1="$LAST"
poll "P2 (~1.2 km) has an offer" 10 '"offerId"' GET /api/delivery/offers "$(cat "$PAR/tok_P2")"; R2="$LAST"
[ "$(offers P3 | body)" = "[]" ] && [ "$(offers P4 | body)" = "[]" ] && ok "P3 (~2.5 km) and P4 (~4.5 km) got none (2 offers per wave)" || bad "farther partners" "$(offers P3) $(offers P4)"
await "P1: DELIVERY_OFFER notification" P1 8 "$(both '"type":"DELIVERY_OFFER"' "$ON1")"
has "the offer shows the store and distance" "$(both '"distanceKm":0\.[0-9]' '"store":\{')" "$R1"
echo "$R1" | grep -q '919811122233\|Residency Road\|Tracking Shopper' && bad "an offer reveals no customer details" "$R1" || ok "an offer reveals no customer details"
DEL1=$(echo "$R1" | body | json deliveryId)

echo "== Acceptance: two partners race for the same delivery — only one wins"
OF1=$(echo "$R1" | body | json offerId); OF2=$(echo "$R2" | body | json offerId)
call POST /api/delivery/offers/$OF1/accept "$(cat "$PAR/tok_P1")" > "$PAR/race1" & ra=$!
call POST /api/delivery/offers/$OF2/accept "$(cat "$PAR/tok_P2")" > "$PAR/race2" & rb=$!
wait $ra $rb   # only these two: a bare `wait` would also wait for the heartbeat and the open streams
s1=$(cut -d' ' -f1 "$PAR/race1"); s2=$(cut -d' ' -f1 "$PAR/race2")
if [ "$s1" = 200 ] && [ "$s2" = 409 ]; then W=P1; L=P2; elif [ "$s1" = 409 ] && [ "$s2" = 200 ]; then W=P2; L=P1; else W=""; fi
[ -n "$W" ] && ok "exactly one accept won ($W: 200, $L: 409)" || bad "race" "P1 $s1, P2 $s2"
grep -h '"errorCode":"OFFER_NOT_AVAILABLE"' "$PAR/race1" "$PAR/race2" >/dev/null && ok "the loser is told OFFER_NOT_AVAILABLE" || bad "loser error" "$(cat "$PAR/race1" "$PAR/race2")"
W=${W:-P1}; L=${L:-P2}
poll "the order records the partner (first name only)" 8 '"partnerName":"Rider"' GET /api/orders/$O1 "$CTOKEN"
await "customer: PARTNER_ASSIGNED" cust 8 "$(both '"type":"PARTNER_ASSIGNED"' "$ON1")"
[ "$(offers $L | body)" = "[]" ] && ok "the loser's offer was withdrawn" || bad "withdrawn" "$(offers $L)"

echo "== Acceptance: an expired offer moves to the next partner"
O2=$(accepted_order o2); ON2=$(call GET /api/orders/$O2 "$CTOKEN" | body | json orderNumber)
poll "wave 1: $L and P3 get offers for $ON2 (the winner is busy)" 10 '"offerId"' GET /api/delivery/offers "$(cat "$PAR/tok_P3")"
offers $L | grep -q "$ON2" && ok "$L also has the offer" || bad "$L offer" "$(offers $L)"
[ "$(offers P4 | body)" = "[]" ] && ok "P4 (outside 3 km) has none yet" || bad "P4 too early" "$(offers P4)"
poll "nobody answers: after the TTL the offer moves on to P4 (~4.5 km, next radius)" 30 "$ON2" GET /api/delivery/offers "$(cat "$PAR/tok_P4")"
OF4=$(echo "$LAST" | body | json offerId)
[ "$(offers P3 | body)" = "[]" ] && ok "P3's offer expired" || bad "P3 expired" "$(offers P3)"
expect "P4 accepts" 200 "$(call POST /api/delivery/offers/$OF4/accept "$(cat "$PAR/tok_P4")")"
DEL2=$(call GET /api/delivery/deliveries/active "$(cat "$PAR/tok_P4")" | body | json deliveryId)

echo "== Acceptance: pickup commits the stock; the customer sees the partner move"
WT=$(cat "$PAR/tok_$W")
r=$(call GET /api/delivery/deliveries/active "$WT"); has "$W's active delivery has the customer's phone and address" "$(both '919811122233' 'Residency Road')" "$r"
expect "another partner cannot open it" 404 "$(call GET /api/delivery/deliveries/$DEL1 "$(cat "$PAR/tok_P3")")"
expect "picking up before the store is ready → 409" 409 "$(call POST /api/delivery/deliveries/$DEL1/pickup "$WT")"
inv() { call GET /api/vendor/inventory/$RICE "$VTOKEN" | body; }
before=$(inv); oh1=$(echo "$before" | num onHand); rs1=$(echo "$before" | num reserved)
call POST /api/vendor/orders/$O1/ready "$VTOKEN" >/dev/null
await "$W: PICKUP_READY notification" $W 8 "$(both '"type":"PICKUP_READY"' "$ON1")"
poll "the delivery knows the order is ready" 8 '"readyForPickup":true' GET /api/delivery/deliveries/$DEL1 "$WT"
expect "$W picks the order up" 200 "$(call POST /api/delivery/deliveries/$DEL1/pickup "$WT")"
poll "order OUT_FOR_DELIVERY" 8 '"status":"OUT_FOR_DELIVERY"' GET /api/orders/$O1 "$CTOKEN"
await "customer: OUT_FOR_DELIVERY" cust 8 "$(both '"type":"OUT_FOR_DELIVERY"' "$ON1")"
poll "stock committed: on hand $oh1 → $((oh1 - 2)), reserved $rs1 → $((rs1 - 2))" 10 "$(both "\"onHand\":$((oh1 - 2))[,}]" "\"reserved\":$((rs1 - 2))[,}]")" GET /api/vendor/inventory/$RICE "$VTOKEN"
poll "the store order is PICKED_UP" 8 '"status":"PICKED_UP"' GET /api/vendor/orders/$O1 "$VTOKEN"
for step in 1 2 3; do  # drive towards the customer (12.975, 77.60), one report per ~2.2 s
  # JSON prints doubles without trailing zeros (12.9747), so format the same way
  lat=$(awk "BEGIN{printf \"%.4f\", 12.9745 + 0.0002*$step}" | sed 's/0*$//; s/\.$//'); lng=$(awk "BEGIN{printf \"%.4f\", 77.592 + 0.002*$step}" | sed 's/0*$//; s/\.$//')
  setpos $W "$lat" "$lng"; report $W >/dev/null
  await "customer's map gets the partner at $lat,$lng" cust 6 "$(both '"type":"DELIVERY_LOCATION"' "\"latitude\":$lat")"
  sleep 2.2
done
r=$(call GET /api/delivery/orders/$O1/tracking "$CTOKEN")
has "tracking: on the way, partner and live position" "$(both '"status":"PICKED_UP"' "\"latitude\":$lat")" "$r"
has "tracking: an ETA" '"etaMinutes":[0-9]+' "$r"
echo "$r" | grep -q '919800000000' && bad "the customer does not get the partner's phone" "$r" || ok "the customer sees the first name only, no partner phone"
expect "another customer cannot track it" 404 "$(call GET /api/delivery/orders/$O1/tracking "$C2TOKEN")"
grep -q "\"latitude\":$lat" "$PAR/P3.sse" && bad "nobody else gets the location" "P3 saw it" || ok "nobody else receives the partner's location"

echo "== Acceptance: completing the delivery sets the order to DELIVERED"
expect "admin cannot cancel after pickup → 409" 409 "$(call POST /api/admin/orders/$O1/cancel "$ATOKEN" '{"reason":"too late"}')"
expect "$W delivers" 200 "$(call POST /api/delivery/deliveries/$DEL1/deliver "$WT")"
poll "order DELIVERED" 8 '"status":"DELIVERED"' GET /api/orders/$O1 "$CTOKEN"
await "customer: ORDER_DELIVERED" cust 8 "$(both '"type":"ORDER_DELIVERED"' "$ON1")"
expect "$W is free again" 204 "$(call GET /api/delivery/deliveries/active "$WT")"
has "history shows the delivery" "$ON1" "$(call GET /api/delivery/history "$WT")"
has "summary counts it" '"deliveredToday":[1-9]' "$(call GET /api/delivery/history/summary "$WT")"

echo "== A failed delivery: the order is DELIVERY_FAILED and admins are asked to decide"
call POST /api/vendor/orders/$O2/ready "$VTOKEN" >/dev/null
P4T=$(cat "$PAR/tok_P4")
for _ in $(seq 1 30); do call GET /api/delivery/deliveries/$DEL2 "$P4T" | grep -q '"readyForPickup":true' && break; sleep 0.3; done
expect "P4 picks up" 200 "$(call POST /api/delivery/deliveries/$DEL2/pickup "$P4T")"
expect "fail OTHER without a note → 400" 400 "$(call POST /api/delivery/deliveries/$DEL2/fail "$P4T" '{"reason":"OTHER"}')"
expect "P4 cannot reach the customer" 200 "$(call POST /api/delivery/deliveries/$DEL2/fail "$P4T" '{"reason":"CUSTOMER_UNREACHABLE","note":"Phone off"}')"
poll "order DELIVERY_FAILED" 8 '"status":"DELIVERY_FAILED"' GET /api/orders/$O2 "$CTOKEN"
await "customer: DELIVERY_FAILED" cust 8 "$(both '"type":"DELIVERY_FAILED"' "$ON2")"
await "admins: DELIVERY_FAILED_ADMIN" admin 8 "$(both '"type":"DELIVERY_FAILED_ADMIN"' "$ON2")"

echo "== No partner available: managers are alerted and assign one by hand; a cancellation releases the partner"
for p in P1 P2 P3 P4; do offline $p >/dev/null; done
expect "an offline partner's location is refused" 409 "$(report P3)"
O3=$(accepted_order o3); ON3=$(call GET /api/orders/$O3 "$CTOKEN" | body | json orderNumber)
await "admins: NO_DELIVERY_PARTNER" admin 15 "$(both '"type":"NO_DELIVERY_PARTNER"' "$ON3")"
poll "the delivery is flagged assignmentFailed" 8 "$(both '"assignmentFailed":true' "$ON3")" GET "/api/admin/deliveries?assignmentFailed=true&q=$ON3" "$ATOKEN"
DEL3=$(echo "$LAST" | body | json deliveryId)
online P3
poll "P3 is listed online for admins" 8 "$(cat "$PAR/id_P3")" GET /api/admin/delivery-partners/online "$ATOKEN"
expect "a partner cannot reassign" 403 "$(call POST /api/admin/deliveries/$DEL3/reassign "$(cat "$PAR/tok_P3")" '{}')"
expect "admin assigns P3 by hand" 200 "$(call POST /api/admin/deliveries/$DEL3/reassign "$ATOKEN" '{"partnerId":"'"$(cat "$PAR/id_P3")"'","reason":"Nearest rider"}')"
poll "P3 now has the delivery" 8 "$ON3" GET /api/delivery/deliveries/active "$(cat "$PAR/tok_P3")"
expect "admin cancels the order before pickup" 200 "$(call POST /api/admin/orders/$O3/cancel "$ATOKEN" '{"reason":"Customer called"}')"
await "P3: DELIVERY_CANCELLED" P3 10 "$(both '"type":"DELIVERY_CANCELLED"' "$ON3")"
poll "P3 is released" 8 '^204' GET /api/delivery/deliveries/active "$(cat "$PAR/tok_P3")"
offline P3 >/dev/null

echo "== Roles and internal endpoints"
expect "a customer cannot use the partner API" 403 "$(call GET /api/delivery/offers "$CTOKEN")"
expect "a customer cannot use the admin delivery monitor" 403 "$(call GET /api/admin/deliveries "$CTOKEN")"
expect "/internal/** is not routed by the gateway" 404 "$(call GET /internal/vendors/$VID/store "$CTOKEN")"

rm -f "$VJAR"
echo
echo "Passed: $PASS   Failed: $FAIL   Skipped: $SKIP"
[ "$FAIL" -eq 0 ]
