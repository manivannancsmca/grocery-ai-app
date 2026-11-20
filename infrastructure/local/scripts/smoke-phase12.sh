#!/usr/bin/env bash
# =====================================================================================
# Phase 12 end-to-end smoke test — coupons, delivered orders, reviews, returns, purchasing, email, the vendor /
# restaurant / delivery assistants (scripted model) and reports. Payments use the simulator.
# Prereq (short return escalation so the check fits in a minute; the assistant uses the deterministic model):
#   cd infrastructure/local && AI_PROVIDER=scripted RETURN_DECISION_TIMEOUT=30s RETURN_ESCALATION_INTERVAL=5s \
#     docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders \
#     --profile realtime --profile reviews --profile reporting --profile assistant up -d --build
# Run (Git Bash):  bash infrastructure/local/scripts/smoke-phase12.sh
# The store, partner and customers stand in Mysuru (12.30, 76.64), away from the other smoke tests' MG Road stores.
# Exit code 0 = every check passed. Safe to re-run: every run uses its own users, store, names and coupon codes.
# =====================================================================================
set -uo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
MAILPIT="${MAILPIT_URL:-http://localhost:8025}"
ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-admin@grocery.local}"
ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-Admin@12345}"
RUN="$(date +%s)"; TAG="m${RUN: -6}"; TODAY="$(TZ=Asia/Kolkata date +%F)"
PASS=0; FAIL=0; SKIP=0
PAR="$(mktemp -d)"
declare -A STREAM_PID=()
HEARTBEAT_PID=""
LA=12.30; LO=76.64     # the store

ok()   { echo "  ✔ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ✘ $1"; echo "    → $2" | head -c 900; echo; FAIL=$((FAIL+1)); }
skip() { echo "  – $1 (skipped: $2)"; SKIP=$((SKIP+1)); }
json() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
num()  { grep -o "\"$1\":-\?[0-9.]*" | head -1 | cut -d: -f2; }
claims() { local p; p=$(echo "$1" | cut -d. -f2 | tr '_-' '/+'); while [ $(( ${#p} % 4 )) -ne 0 ]; do p="$p="; done; echo "$p" | base64 -d 2>/dev/null; }
call() {  # method path token [body] [extra header] -> "STATUS BODY"
  local method=$1 path=$2 token=${3:-} body=${4:-} header=${5:-}
  local out; out=$(mktemp)
  local args=(-s -o "$out" -w '%{http_code}' -X "$method" "$GW$path" -H "X-Correlation-Id: smoke12-$RUN-$RANDOM")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  [ -n "$header" ] && args+=(-H "$header")
  local status; status=$(curl "${args[@]}"); echo "$status $(cat "$out")"; rm -f "$out"
}
body() { cut -d' ' -f2-; }
expect() { local status=${3%% *}; if [ "$status" = "$2" ]; then ok "$1 ($status)"; else bad "$1 (expected $2, got $status)" "${3#* }"; fi; }
has() { if echo "$3" | grep -Eq -- "$2"; then ok "$1"; else bad "$1" "$3"; fi; }
hasnt() { if echo "$3" | grep -Eq -- "$2"; then bad "$1" "$3"; else ok "$1"; fi; }
both() { echo "($1.*$2|$2.*$1)"; }
money() { printf '%.2f' "$1"; }
pyq() { python -c "import json,sys; d=json.load(sys.stdin); print($1)" 2>/dev/null; }   # a Python expression over JSON on stdin
poll() { # "label" maxSeconds "regex" method path [token]
  local label=$1 max=$2 pattern=$3 method=$4 path=$5 token=${6:-} r start; start=$(date +%s%N)
  for _ in $(seq 1 $((max * 2))); do
    r=$(call "$method" "$path" "$token")
    if echo "$r" | grep -Eq -- "$pattern"; then ok "$label ($(( ($(date +%s%N) - start) / 1000000 )) ms)"; LAST="$r"; return 0; fi
    sleep 0.5
  done
  bad "$label" "$r"; LAST="$r"; return 1
}
register() { curl -s ${4:+-c "$4"} -X POST "$GW/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"Secret123\",\"fullName\":\"$3\",\"phone\":\"+919876543210\",\"role\":\"$2\"}" | json accessToken; }
refresh() { curl -s -b "$1" -c "$1" -X POST "$GW/api/auth/refresh" -H 'X-Requested-With: XMLHttpRequest' | json accessToken; }
login() { call POST /api/auth/login "" "{\"email\":\"$1\",\"password\":\"$2\"}" | body | json accessToken; }
admin_user() { call POST /api/admin/users "$ATOKEN" "{\"email\":\"$1\",\"fullName\":\"$2 $RUN\",\"password\":\"Admin1234\",\"roles\":[\"$2\"]}" >/dev/null; login "$1" Admin1234; }
key() { echo "smoke12-$RUN-$1"; }
open_stream() { : > "$PAR/$1.sse"; curl -sN --max-time 900 -H "Authorization: Bearer $2" -H 'Accept: text/event-stream' "$GW/api/notifications/stream" > "$PAR/$1.sse" 2>/dev/null & STREAM_PID[$1]=$!; }
await() { local label=$1 name=$2 max=$3 pattern=$4 start; start=$(date +%s%N)
  for _ in $(seq 1 $((max * 10))); do grep -Eq -- "$pattern" "$PAR/$name.sse" && { ok "$label ($(( ($(date +%s%N) - start) / 1000000 )) ms)"; return 0; }; sleep 0.1; done
  bad "$label" "$(tail -c 400 "$PAR/$name.sse")"; return 1; }
mails() { curl -s "$MAILPIT/api/v1/search?query=to%3A$1&limit=50"; }   # Mailpit search by recipient
chat() { # agent token message -> SSE in $PAR/chat.sse
  local payload; payload=$(python -c 'import json,sys; print(json.dumps({"message":sys.argv[1]}))' "$3")
  curl -sN --max-time 90 -o "$PAR/chat.sse" -X POST "$GW/api/ai/agents/$1/chat" -H "Authorization: Bearer $2" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d "$payload"
}
chat_approval() { grep -A1 '^event: *approval' "$PAR/chat.sse" | grep '^data:' | tail -1 | sed 's/^data: *//'; }
customer() { # email name -> token (with an address 1.5 km from the store)
  local t; t=$(register "$1" CUSTOMER "$2")
  for _ in $(seq 1 60); do [ "$(call GET /api/customers/me "$t" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
  call POST /api/customers/me/addresses "$t" '{"label":"Home","recipientName":"'"$2"'","phone":"+919876543210","line1":"7 Palace Road","city":"Mysuru","state":"Karnataka","postalCode":"570001","country":"IN","latitude":12.31,"longitude":76.65}' >/dev/null
  echo "$t"
}
fill_cart() { call DELETE /api/cart "$1" >/dev/null; call POST /api/cart/items "$1" '{"type":"PRODUCT","productId":"'"$RICE"'","quantity":'"$2"'}' >/dev/null; }
preview_total() { call GET /api/cart/preview "$1" | body | grep -o '"total":[0-9.]*' | tail -1 | cut -d: -f2; }
place() { # token key -> "STATUS BODY"
  local t; t=$(preview_total "$1"); call POST /api/orders "$1" "{\"expectedTotal\":$t}" "Idempotency-Key: $(key "$2")"
}
pay() { # token orderId
  for _ in $(seq 1 60); do [ "$(call GET /api/payments/orders/$2/checkout "$1" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
  local b; b=$(call POST /api/payments/simulator/orders/$2/pay "$1" '{"outcome":"SUCCESS"}' | body)
  call POST /api/payments/razorpay/verify "$1" "{\"razorpayOrderId\":\"$(echo "$b" | json razorpayOrderId)\",\"razorpayPaymentId\":\"$(echo "$b" | json razorpayPaymentId)\",\"razorpaySignature\":\"$(echo "$b" | json razorpaySignature)\"}" >/dev/null
  for _ in $(seq 1 60); do call GET /api/orders/$2 "$1" | grep -q '"status":"CONFIRMED"' && break; sleep 0.25; done
}
report_pos() { call PUT /api/delivery/partners/me/location "$WT" "{\"latitude\":12.301,\"longitude\":76.641,\"accuracyMeters\":8}" >/dev/null; }
heartbeat() { while true; do [ -e "$PAR/online" ] && report_pos; sleep 8; done; }
stock() { call GET /api/vendor/inventory/$RICE "$VTOKEN" | body | num onHand; }
cleanup() { [ -n "$HEARTBEAT_PID" ] && kill "$HEARTBEAT_PID" 2>/dev/null; for n in "${!STREAM_PID[@]}"; do kill "${STREAM_PID[$n]}" 2>/dev/null; done; rm -rf "$PAR"; }
trap cleanup EXIT

echo "== Preflight"
bash "$(dirname "$0")/check-connectors.sh" > "$PAR/conn" 2>&1 && ok "all connector tasks RUNNING" || { bad "connector tasks" "$(cat "$PAR/conn")"; exit 1; }
grep -q "review-outbox" "$PAR/conn" && ok "the review-outbox connector is registered" || bad "review-outbox" "$(cat "$PAR/conn")"
for s in gateway review reporting pricing cart order payment vendor inventory notification ai; do expect "health/$s" 200 "$(call GET /api/health/$s)"; done
curl -sf "$MAILPIT/api/v1/info" >/dev/null && ok "Mailpit is up ($MAILPIT)" || bad "Mailpit" "not reachable at $MAILPIT"
ATOKEN=$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD"); [ -n "$ATOKEN" ] && ok "admin login" || bad "admin login" "no token"
MTOKEN=$(admin_user "manager12-$RUN@example.com" DELIVERY_MANAGER); [ -n "$MTOKEN" ] && ok "a DELIVERY_MANAGER" || bad "manager" "no token"

echo "== Setup: a store in Mysuru with a stocked product, a delivery partner next to it, four customers"
CAT=$(call POST /api/admin/categories "$ATOKEN" "{\"name\":\"Smoke Phase12 $RUN\"}" | body | json id)
VJAR="$(mktemp)"; VTOKEN=$(register "vendor12-$RUN@example.com" VENDOR "Mysuru Mart" "$VJAR")
for _ in $(seq 1 60); do VID=$(call GET /api/vendors/me "$VTOKEN" | body | json id); [ -n "$VID" ] && break; sleep 0.25; done
call PUT /api/vendors/me "$VTOKEN" '{"businessName":"Mysuru Mart '"$RUN"'","legalName":"Mysuru Mart Pvt Ltd","gstin":"29ABCDE1234F1Z5","fssaiLicense":"12345678901234","contactEmail":"ops@mysuru.in","contactPhone":"+919876543210","address":{"line1":"1 Sayyaji Rao Road","city":"Mysuru","state":"Karnataka","postalCode":"570001","country":"IN","latitude":'"$LA"',"longitude":'"$LO"'}}' >/dev/null
call POST /api/vendors/me/submit "$VTOKEN" >/dev/null; call POST /api/admin/vendors/$VID/approve "$ATOKEN" >/dev/null
for _ in $(seq 1 20); do VTOKEN=$(refresh "$VJAR"); claims "$VTOKEN" | grep -q "\"vendor_id\":\"$VID\"" && break; sleep 0.5; done
RICE=$(call POST /api/vendor/products "$VTOKEN" '{"name":"Sona Masuri '"$TAG"'","categoryId":"'"$CAT"'","unitValue":1,"unitOfMeasure":"KG","foodType":"VEG"}' | body | json id)
call POST /api/vendor/products/$RICE/publish "$VTOKEN" >/dev/null
for _ in $(seq 1 60); do [ "$(call GET /api/vendor/inventory/$RICE "$VTOKEN" | cut -d' ' -f1)" = 200 ] && [ "$(call GET /api/vendor/prices/$RICE "$VTOKEN" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
call PUT /api/vendor/prices/$RICE "$VTOKEN" '{"mrp":120.00,"sellingPrice":100.00}' >/dev/null
call POST /api/vendor/inventory/$RICE/adjustments "$VTOKEN" '{"type":"SET","quantity":50,"reason":"STOCK_COUNT"}' >/dev/null
poll "product \"Sona Masuri $TAG\" in stock" 20 '"inStock":true' GET /api/catalog/products/$RICE
WT=$(register "rider12-$RUN@example.com" DELIVERY_PARTNER "Ravi Rider")
for _ in $(seq 1 60); do WID=$(call GET /api/delivery/partners/me "$WT" | body | json id); [ -n "$WID" ] && break; sleep 0.25; done
call PUT /api/delivery/partners/me "$WT" '{"fullName":"Ravi Rider","phone":"+919800000000","city":"Mysuru","vehicleType":"BICYCLE"}' >/dev/null
call POST /api/delivery/partners/me/submit "$WT" >/dev/null; call POST /api/admin/delivery-partners/$WID/approve "$ATOKEN" >/dev/null
sleep 1; call PUT /api/delivery/partners/me/availability "$WT" '{"online":true}' >/dev/null; touch "$PAR/online"; report_pos
heartbeat & HEARTBEAT_PID=$!
has "the partner is online next to the store" '"online":true' "$(call GET /api/delivery/partners/me/status "$WT")"
C1E="c1-$RUN@example.com"; C1=$(customer "$C1E" "Anita Kumar")
C2=$(customer "c2-$RUN@example.com" "Bala Rao"); C3=$(customer "c3-$RUN@example.com" "Chitra Iyer")
C4E="c4-$RUN@example.com"; C4=$(customer "$C4E" "Dev Menon")
[ -n "$C1" ] && [ -n "$C4" ] && ok "four customers with an address 1.5 km away" || bad "customers" "missing tokens"
open_stream c1 "$C1"; open_stream vendor "$VTOKEN"; open_stream admin "$ATOKEN"

echo "== Coupons: offers, minimum order, discount in preview and order"
VALID_UNTIL="$(date -u -d '+1 day' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || python -c 'import datetime;print((datetime.datetime.utcnow()+datetime.timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%SZ"))')"
VS="VS${RUN: -6}"; ONE="ONE${RUN: -6}"
expect "the vendor creates a store coupon $VS (₹20 off, min ₹150, public)" 201 "$(call POST /api/vendor/coupons "$VTOKEN" '{"code":"'"$VS"'","name":"Twenty off","scope":"STORE","discountType":"FLAT","value":20.00,"minOrderSubtotal":150.00,"validUntil":"'"$VALID_UNTIL"'","perCustomerLimit":5,"public":true}')"
expect "an admin creates platform coupon $ONE (10 %, one use in total)" 201 "$(call POST /api/admin/coupons "$ATOKEN" '{"code":"'"$ONE"'","name":"Only one","scope":"PLATFORM","storeType":"ANY","discountType":"PERCENT","value":10,"minOrderSubtotal":0,"validUntil":"'"$VALID_UNTIL"'","totalUsageLimit":1,"perCustomerLimit":1,"public":false}')"
expect "a vendor cannot create a platform coupon" 403 "$(call POST /api/vendor/coupons "$VTOKEN" '{"code":"X'"$RUN"'","name":"x","scope":"PLATFORM","discountType":"FLAT","value":5,"validUntil":"'"$VALID_UNTIL"'"}')"
has "customers see the store's public offer" "\"code\":\"$VS\"" "$(call GET "/api/coupons?storeId=$VID" "$C1")"
fill_cart "$C1" 1
r=$(call PUT /api/cart/coupon "$C1" '{"code":"'"${VS,,}"'"}'); expect "below the minimum the coupon is refused" 422 "$r"
has "… with reason COUPON_MIN_ORDER and the minimum" "$(both '"reason":"COUPON_MIN_ORDER"' '"minOrderSubtotal":150')" "$r"
fill_cart "$C1" 2
expect "with 2 kg (₹200) the coupon applies (code is case-insensitive)" 200 "$(call PUT /api/cart/coupon "$C1" '{"code":"'"${VS,,}"'"}')"
P=$(call GET /api/cart/preview "$C1"); FEE=$(echo "$P" | body | grep -o '"fee":[0-9.]*' | head -1 | cut -d: -f2); FEE=${FEE:-0}
has "the preview shows the coupon, ₹20.00 off" "$(both "\"code\":\"$VS\"" '"discount":20\.0')" "$P"
T=$(preview_total "$C1"); [ "$(money "$T")" = "$(python -c "print('%.2f' % (200 + $FEE - 20))")" ] && ok "total = subtotal + fee − discount (₹$T)" || bad "preview total" "$T with fee $FEE"
r=$(place "$C1" a); expect "order A is placed with the coupon" 201 "$r"
OA=$(echo "$r" | body | json id); ONA=$(echo "$r" | body | json orderNumber)
has "… and carries it (couponCode, discount ₹20.00)" "$(both "\"couponCode\":\"$VS\"" '"discount":20\.0')" "$r"
TA=$(echo "$r" | body | grep -o '"total":[0-9.]*' | tail -1 | cut -d: -f2)

echo "== Coupons: the usage limit holds under concurrent orders; a cancelled order frees the use"
fill_cart "$C2" 2; fill_cart "$C3" 2
expect "customer 2 applies $ONE" 200 "$(call PUT /api/cart/coupon "$C2" '{"code":"'"$ONE"'"}')"
expect "customer 3 applies $ONE" 200 "$(call PUT /api/cart/coupon "$C3" '{"code":"'"$ONE"'"}')"
place "$C2" race2 > "$PAR/r2" & p2=$!; place "$C3" race3 > "$PAR/r3" & p3=$!; wait $p2 $p3
s2=$(cut -d' ' -f1 < "$PAR/r2"); s3=$(cut -d' ' -f1 < "$PAR/r3")
if { [ "$s2" = 201 ] && [ "$s3" = 409 ]; } || { [ "$s2" = 409 ] && [ "$s3" = 201 ]; }; then ok "two simultaneous orders: exactly one gets the single-use coupon ($s2/$s3)"; else bad "coupon race" "$s2 / $s3"; fi
if [ "$s2" = 201 ]; then WIN=$C2; WR=$PAR/r2; LOSE=$C3; LR=$PAR/r3; else WIN=$C3; WR=$PAR/r3; LOSE=$C2; LR=$PAR/r2; fi
has "… the other gets COUPON_NOT_APPLICABLE (COUPON_USAGE_LIMIT)" '"reason":"COUPON_USAGE_LIMIT"' "$(cat "$LR")"
OW=$(body < "$WR" | json id)
expect "the winner cancels the order" 200 "$(call POST /api/orders/$OW/cancel "$WIN" '{"reason":"Changed my mind"}')"
for i in $(seq 1 40); do r=$(place "$LOSE" "retry$i"); [ "${r%% *}" = 201 ] && break; sleep 1; done
expect "the use is freed: the other customer now gets the coupon" 201 "$r"
OL=$(echo "$r" | body | json id)

echo "== Email preferences: a disabled category is not emailed"
expect "customer 4 turns order emails off" 200 "$(call PUT /api/notifications/preferences "$C4" '{"email":{"orders":false,"account":true,"approvals":true}}')"
fill_cart "$C4" 2; r=$(place "$C4" d); OD=$(echo "$r" | body | json id); OND=$(echo "$r" | body | json orderNumber)
pay "$C4" "$OD"; pay "$C1" "$OA"
has "order A is CONFIRMED (paid)" '"status":"CONFIRMED"' "$(call GET /api/orders/$OA "$C1")"
for _ in $(seq 1 40); do mails "$C1E" | grep -q "$ONA" && break; sleep 0.5; done
has "customer 1's order confirmation arrived in Mailpit" "$ONA" "$(mails "$C1E")"
sleep 5
hasnt "customer 4 (order emails off) got no confirmation email" "$OND" "$(mails "$C4E")"

echo "== Delivering order A (store accepts, partner picks up and delivers)"
call POST /api/vendor/orders/$OA/accept "$VTOKEN" '{"readyInMinutes":10}' >/dev/null
call POST /api/vendor/orders/$OA/preparing "$VTOKEN" >/dev/null; call POST /api/vendor/orders/$OA/ready "$VTOKEN" >/dev/null
poll "the partner is offered order A" 40 "$ONA" GET /api/delivery/offers "$WT"
OFF=$(echo "$LAST" | body | grep -o "\"offerId\":\"[^\"]*\"[^}]*$ONA" | head -1 | cut -d'"' -f4)
r=$(call POST /api/delivery/offers/$OFF/accept "$WT"); expect "the partner accepts" 200 "$r"
DEL=$(echo "$r" | body | json deliveryId); [ -n "$DEL" ] || DEL=$(echo "$LAST" | body | grep -o "\"offerId\":\"$OFF\",\"deliveryId\":\"[^\"]*\"" | cut -d'"' -f8)
expect "pickup" 200 "$(call POST /api/delivery/deliveries/$DEL/pickup "$WT")"
expect "deliver" 200 "$(call POST /api/delivery/deliveries/$DEL/deliver "$WT")"
poll "order A is DELIVERED" 20 '"status":"DELIVERED"' GET /api/orders/$OA "$C1"

echo "== Reviews: only what was received; moderation; store reply; rating in search"
r=$(call GET "/api/reviews/eligible?orderId=$OA" "$C1"); expect "what customer 1 may review" 200 "$r"
has "… the product and the store" "$(both "\"targetId\":\"$RICE\"" "\"targetId\":\"$VID\"")" "$r"
echo "$r" | grep -q "\"targetId\":\"$WID\"" && ok "… including the delivery partner" || bad "partner eligible" "$r"
r=$(call POST /api/reviews "$C1" '{"orderId":"'"$OA"'","targetType":"PRODUCT","targetId":"'"$RICE"'","rating":5,"title":"Great rice","comment":"Fresh and well packed."}')
expect "a 5★ review of the product" 201 "$r"; RV=$(echo "$r" | body | json id)
has "… published at once" '"status":"PUBLISHED"' "$r"
expect "reviewing it twice → 409 ALREADY_REVIEWED" 409 "$(call POST /api/reviews "$C1" '{"orderId":"'"$OA"'","targetType":"PRODUCT","targetId":"'"$RICE"'","rating":4}')"
expect "something not in the order → 422 REVIEW_NOT_ELIGIBLE" 422 "$(call POST /api/reviews "$C1" '{"orderId":"'"$OA"'","targetType":"PRODUCT","targetId":"'"$CAT"'","rating":4}')"
expect "an order that was not delivered → 422" 422 "$(call POST /api/reviews "$C4" '{"orderId":"'"$OD"'","targetType":"PRODUCT","targetId":"'"$RICE"'","rating":4}')"
r=$(call POST /api/reviews "$C1" '{"orderId":"'"$OA"'","targetType":"VENDOR","targetId":"'"$VID"'","rating":4,"title":"Good store","comment":"Order more at http://cheap-rice.example"}')
expect "a store review with a link" 201 "$r"; RS=$(echo "$r" | body | json id)
has "… is FLAGGED by automatic moderation" '"status":"FLAGGED"' "$r"
hasnt "… and hidden from the public" "$RS" "$(call GET "/api/reviews?targetType=VENDOR&targetId=$VID")"
await "admins are asked to moderate it" admin 15 "$(both '"type":"REVIEW_FLAGGED"' 'needs moderation')"
expect "an admin publishes it" 200 "$(call POST /api/admin/reviews/$RS/publish "$ATOKEN")"
poll "… now it is public (anonymous read)" 10 "$RS" GET "/api/reviews?targetType=VENDOR&targetId=$VID"
has "… shown as \"Anita K.\"" '"authorName":"Anita K\."' "$LAST"
await "the store is told about its new review" vendor 15 '"type":"NEW_REVIEW"'
expect "the store replies to the product review" 200 "$(call PUT /api/store/reviews/$RV/reply "$VTOKEN" '{"text":"Thank you, Anita!"}')"
has "the public product reviews show the reply" "$(both "$RV" 'Thank you, Anita')" "$(call GET "/api/reviews?targetType=PRODUCT&targetId=$RICE")"
has "the product's summary: 5.00 from 1 review" "$(both '"averageRating":5' '"ratingCount":1')" "$(call GET "/api/reviews/summary?targetType=PRODUCT&targetId=$RICE")"
poll "the rating appears in search" 20 "$(both "\"id\":\"$RICE\"" '"rating":5')" GET "/api/search/products?q=sona%20$TAG"

echo "== Returns: prorated refund, restock, escalation"
before=$(stock)
r=$(call POST /api/orders/$OA/returns "$C1" '{"items":[{"itemId":"'"$RICE"'","quantity":1}],"reason":"DAMAGED","note":"Torn packet"}')
expect "customer 1 returns 1 of the 2 kg" 201 "$r"; RT1=$(echo "$r" | body | json id)
has "… refund ₹90.00 (₹100 less its share of the ₹20 coupon)" '"refundAmount":90\.0' "$r"
expect "a second open request on the same order → 422" 422 "$(call POST /api/orders/$OA/returns "$C1" '{"items":[{"itemId":"'"$RICE"'","quantity":1}],"reason":"OTHER"}')"
await "the store is notified" vendor 15 '"type":"RETURN_REQUESTED"'
has "the store sees it" "$RT1" "$(call GET /api/store/returns "$VTOKEN")"
expect "the store approves it and restocks" 200 "$(call POST /api/store/returns/$RT1/approve "$VTOKEN" '{"restock":true}')"
poll "the refund is paid: return REFUNDED" 30 '"status":"REFUNDED"' GET /api/orders/$OA/returns "$C1"
has "payment-service refunded ₹90.00 for that return" "$(both "\"returnId\":\"$RT1\"" '"amount":90\.0')" "$(call GET "/api/admin/payments?orderId=$OA" "$ATOKEN")"
for _ in $(seq 1 20); do [ "$(stock)" = "$((before + 1))" ] && break; sleep 0.5; done
[ "$(stock)" = "$((before + 1))" ] && ok "the returned kilo is back in stock ($before → $(stock))" || bad "restock" "$before → $(stock)"
await "customer 1: return approved" c1 15 '"type":"RETURN_APPROVED"'
for _ in $(seq 1 40); do mails "$C1E" | grep -qi "refund\|return" && break; sleep 0.5; done
has "… and a refund email in Mailpit" "[Rr]efund|[Rr]eturn" "$(mails "$C1E" | grep -o '"Subject":"[^"]*"')"
r=$(call POST /api/orders/$OA/returns "$C1" '{"items":[{"itemId":"'"$RICE"'","quantity":1}],"reason":"QUALITY"}')
expect "the last kilo is returned too" 201 "$r"; RT2=$(echo "$r" | body | json id)
exp=$(python -c "print('%.2f' % (90 + $FEE))"); got=$(echo "$r" | body | pyq "'%.2f' % d['refundAmount']")
[ "$got" = "$exp" ] && ok "… a full return also refunds the delivery fee (₹$exp)" || bad "full-return refund" "got $got, expected $exp"
poll "the store does not decide in time: ESCALATED" 60 "$(both "$RT2" '"status":"ESCALATED"')" GET /api/admin/returns "$ATOKEN"
await "admins are told" admin 15 '"type":"RETURN_ESCALATED"'
expect "the store can no longer decide it" 409 "$(call POST /api/store/returns/$RT2/approve "$VTOKEN" '{"restock":false}')"
expect "an admin rejects it" 200 "$(call POST /api/admin/returns/$RT2/reject "$ATOKEN" '{"comment":"The photos show no defect."}')"
await "customer 1: return rejected" c1 15 '"type":"RETURN_REJECTED"'

echo "== Purchasing: supplier, purchase order, receiving increases stock once"
r=$(call POST /api/vendor/suppliers "$VTOKEN" '{"name":"Mandya Mills '"$RUN"'","contactName":"Suresh","phone":"+919811111111","email":"orders@mandya.example","leadTimeDays":2}')
expect "a supplier" 201 "$r"; SUP=$(echo "$r" | body | json id)
r=$(call POST /api/vendor/purchase-orders "$VTOKEN" '{"supplierId":"'"$SUP"'","expectedAt":"'"$TODAY"'","lines":[{"productId":"'"$RICE"'","orderedQuantity":10,"unitCost":60.00}]}')
expect "a purchase order (DRAFT)" 201 "$r"; PO=$(echo "$r" | body | json id); PON=$(echo "$r" | body | json number)
expect "send it" 200 "$(call POST /api/vendor/purchase-orders/$PO/send "$VTOKEN" '{}')"
before=$(stock)
r=$(call POST /api/vendor/purchase-orders/$PO/receive "$VTOKEN" '{"lines":[{"productId":"'"$RICE"'","quantity":4}],"note":"First truck"}' "Idempotency-Key: $(key recv1)")
expect "receive 4 of 10" 200 "$r"; has "… PARTIALLY_RECEIVED" '"status":"PARTIALLY_RECEIVED"' "$r"
poll "stock +4 (GoodsReceived → inventory)" 20 "\"onHand\":$((before + 4))[,}]" GET /api/vendor/inventory/$RICE "$VTOKEN"
expect "the same receipt again (retry, same key)" 200 "$(call POST /api/vendor/purchase-orders/$PO/receive "$VTOKEN" '{"lines":[{"productId":"'"$RICE"'","quantity":4}],"note":"First truck"}' "Idempotency-Key: $(key recv1)")"
sleep 3; [ "$(stock)" = "$((before + 4))" ] && ok "… changes nothing (still +4)" || bad "receipt idempotency" "$before → $(stock)"
expect "receiving more than is open → 422 RECEIPT_EXCEEDS_ORDERED" 422 "$(call POST /api/vendor/purchase-orders/$PO/receive "$VTOKEN" '{"lines":[{"productId":"'"$RICE"'","quantity":7}]}' "Idempotency-Key: $(key recv2)")"
has "receive the remaining 6: RECEIVED" '"status":"RECEIVED"' "$(call POST /api/vendor/purchase-orders/$PO/receive "$VTOKEN" '{"lines":[{"productId":"'"$RICE"'","quantity":6}]}' "Idempotency-Key: $(key recv3)")"
poll "stock +10 in total" 20 "\"onHand\":$((before + 10))[,}]" GET /api/vendor/inventory/$RICE "$VTOKEN"

echo "== Assistants (scripted model): vendor, restaurant, delivery manager — nothing runs before approval"
chat vendor "$VTOKEN" "set stock sona masuri $TAG to 30"
A=$(chat_approval); AV=$(echo "$A" | json id)
has "the vendor assistant proposes ADJUST_STOCK" "$(both '"action":"ADJUST_STOCK"' '"status":"PENDING_APPROVAL"')" "$A"
[ "$(stock)" != 30 ] && ok "… the stock is unchanged until approved ($(stock))" || bad "no change before approval" "$(stock)"
has "the vendor approves: COMPLETED" '"status":"COMPLETED"' "$(call POST /api/approvals/$AV/approve "$VTOKEN" '{}')"
poll "stock is now 30" 10 '"onHand":30[,}]' GET /api/vendor/inventory/$RICE "$VTOKEN"
RJAR="$(mktemp)"; RTOKEN=$(register "rest12-$RUN@example.com" RESTAURANT_OWNER "Mysuru Kitchen" "$RJAR")
for _ in $(seq 1 60); do REST=$(call GET /api/restaurants/me "$RTOKEN" | body | json id); [ -n "$REST" ] && break; sleep 0.25; done
HOURS=$(for d in MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY SUNDAY; do printf '{"dayOfWeek":"%s","opensAt":"00:00","closesAt":"23:59"},' $d; done)
call PUT /api/restaurants/me "$RTOKEN" '{"name":"Mysuru Kitchen '"$RUN"'","cuisines":["SOUTH_INDIAN"],"fssaiLicense":"12345678901234","contactEmail":"k@mysuru.in","contactPhone":"+919876543210","address":{"line1":"3 Temple Road","city":"Mysuru","state":"Karnataka","postalCode":"570001","country":"IN","latitude":12.305,"longitude":76.645},"openingHours":['"${HOURS%,}"']}' >/dev/null
MCAT=$(call POST /api/restaurant/menu/categories "$RTOKEN" '{"name":"Dosa"}' | body | json id)
DOSA=$(call POST /api/restaurant/menu/items "$RTOKEN" '{"categoryId":"'"$MCAT"'","name":"Mysore Masala Dosa '"$TAG"'","price":90.00,"foodType":"VEG"}' | body | json id)
call POST /api/restaurants/me/submit "$RTOKEN" >/dev/null; call POST /api/admin/restaurants/$REST/approve "$ATOKEN" >/dev/null
for _ in $(seq 1 20); do RTOKEN=$(refresh "$RJAR"); claims "$RTOKEN" | grep -q "\"restaurant_id\":\"$REST\"" && break; sleep 0.5; done
chat restaurant "$RTOKEN" "mark mysore masala dosa $TAG sold out"
A=$(chat_approval); AR=$(echo "$A" | json id)
has "the restaurant assistant proposes SET_MENU_ITEM_AVAILABILITY" '"action":"SET_MENU_ITEM_AVAILABILITY"' "$A"
has "… the dish is still available until approved" "$(both "\"id\":\"$DOSA\"" '"available":true')" "$(call GET /api/restaurant/menu "$RTOKEN")"
has "the owner approves: COMPLETED" '"status":"COMPLETED"' "$(call POST /api/approvals/$AR/approve "$RTOKEN" '{}')"
has "the dish is sold out" "\"id\":\"$DOSA\"[^}]*\"available\":false|\"available\":false[^}]*\"id\":\"$DOSA\"" "$(call GET /api/restaurant/menu "$RTOKEN")"
rm -f "$PAR/online"; call PUT /api/delivery/partners/me/availability "$WT" '{"online":false}' >/dev/null
call POST /api/vendor/orders/$OD/accept "$VTOKEN" '{"readyInMinutes":10}' >/dev/null
poll "order $OND waits for a partner (delivery SEARCHING)" 20 "$(both "$OND" '"status":"SEARCHING"')" GET "/api/admin/deliveries?q=$OND" "$ATOKEN"
chat delivery "$MTOKEN" "reassign $OND"
A=$(chat_approval); AD=$(echo "$A" | json id)
has "the delivery assistant proposes REASSIGN_DELIVERY for a manager to decide" "$(both '"action":"REASSIGN_DELIVERY"' 'DELIVERY_MANAGER')" "$A"
has "a manager approves: COMPLETED (the search restarts)" '"status":"COMPLETED"' "$(call POST /api/approvals/$AD/approve "$MTOKEN" '{}')"

echo "== Reports match what this run did"
GMV=$(python -c "print('%.2f' % ($TA + $(call GET /api/orders/$OD "$C4" | body | pyq "d['total']")))")
want="2 $GMV 90.00"; for _ in $(seq 1 40); do
  got=$(call GET "/api/admin/reports/sales/daily?from=$TODAY&to=$TODAY&storeId=$VID" "$ATOKEN" | body | pyq "'%d %.2f %.2f' % (sum(x['orders'] for x in d), sum(x['gmv'] for x in d), sum(x['refunds'] for x in d))")
  [ "$got" = "$want" ] && break; sleep 1; done
[ "$got" = "$want" ] && ok "daily sales for this store: 2 paid orders, GMV ₹$GMV, refunds ₹90.00" || bad "daily sales" "got '$got', expected '$want'"
got=$(call GET "/api/admin/reports/promotions?from=$TODAY&to=$TODAY" "$ATOKEN" | body | pyq "[('%d %.2f' % (x['orders'], x['discountGiven'])) for x in d if x['couponCode']=='$VS']")
[ "$got" = "['1 20.00']" ] && ok "promotions: $VS used once, ₹20.00 given" || bad "promotions" "$got"
want="10 600.00"; for _ in $(seq 1 20); do
  got=$(call GET "/api/admin/reports/purchasing?from=$TODAY&to=$TODAY&vendorId=$VID" "$ATOKEN" | body | pyq "'%d %.2f' % (sum(x['units'] for x in d), sum(x['cost'] for x in d))")
  [ "$got" = "$want" ] && break; sleep 1; done
[ "$got" = "$want" ] && ok "purchasing: 10 units, ₹600.00 for this vendor" || bad "purchasing report" "got '$got'"
r=$(curl -s -D - -o "$PAR/csv" -H "Authorization: Bearer $ATOKEN" "$GW/api/admin/reports/overview?from=$TODAY&to=$TODAY&format=csv")
echo "$r" | grep -qi "content-type: text/csv" && head -1 "$PAR/csv" | grep -q "grossSales" && ok "CSV export with a header row" || bad "CSV" "$(head -3 "$PAR/csv")"
expect "customers cannot read reports" 403 "$(call GET "/api/admin/reports/overview" "$C1")"

echo "== Cleanup: cancel this run's open orders"
for pair in "$C4:$OD" "$LOSE:$OL"; do t=${pair%%:*}; o=${pair##*:}; call POST /api/admin/orders/$o/cancel "$ATOKEN" '{"reason":"Smoke test cleanup"}' >/dev/null; done
ok "open orders cancelled"

rm -f "$VJAR" "$RJAR"
echo
echo "Passed: $PASS   Failed: $FAIL   Skipped: $SKIP"
[ "$FAIL" -eq 0 ]
