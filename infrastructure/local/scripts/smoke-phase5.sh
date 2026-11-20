#!/usr/bin/env bash
# =====================================================================================
# Phase 5 end-to-end smoke test — cart & checkout preview, everything through the API gateway.
# Prereq: cd infrastructure/local && docker compose --profile core --profile catalog --profile inventory --profile cart up -d --build
# Run (Git Bash):  bash infrastructure/local/scripts/smoke-phase5.sh
# Exit code 0 = every check passed. Safe to re-run: every run uses its own users, store and names.
# =====================================================================================
set -uo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-admin@grocery.local}"
ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-Admin@12345}"
RUN="$(date +%s)"
PASS=0; FAIL=0
PAR="$(mktemp -d)"

ok()   { echo "  ✔ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ✘ $1"; echo "    → $2" | head -c 900; echo; FAIL=$((FAIL+1)); }
json() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
num()  { grep -o "\"$1\":-\?[0-9.]*" | head -1 | cut -d: -f2; }
claims() { local p; p=$(echo "$1" | cut -d. -f2 | tr '_-' '/+'); while [ $(( ${#p} % 4 )) -ne 0 ]; do p="$p="; done; echo "$p" | base64 -d 2>/dev/null; }
call() {
  local method=$1 path=$2 token=${3:-} body=${4:-}
  local args=(-s -o /tmp/smoke5_body -w '%{http_code}' -X "$method" "$GW$path" -H "X-Correlation-Id: smoke5-$RUN-$RANDOM")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local status; status=$(curl "${args[@]}"); echo "$status $(cat /tmp/smoke5_body)"
}
body() { cut -d' ' -f2-; }
expect() { local status=${3%% *}; if [ "$status" = "$2" ]; then ok "$1 ($status)"; else bad "$1 (expected $2, got $status)" "${3#* }"; fi; }
has() { if echo "$3" | grep -Eq -- "$2"; then ok "$1"; else bad "$1" "$3"; fi; }   # label regex response
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

echo "== Health (public)"
for s in gateway cart pricing inventory catalog-query restaurant vendor customer; do expect "health/$s" 200 "$(call GET /api/health/$s)"; done
ATOKEN=$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD"); [ -n "$ATOKEN" ] && ok "admin login" || bad "admin login" "no token"

echo "== Setup: an approved grocery store with priced, stocked products"
CAT=$(call POST /api/admin/categories "$ATOKEN" "{\"name\":\"Smoke Cart $RUN\"}" | body | json id)
VJAR="$(mktemp)"; VTOKEN=$(register "vendor5-$RUN@example.com" VENDOR "Cart Grocer" "$VJAR")
poll "vendor draft created" 15 '^200 ' GET /api/vendors/me "$VTOKEN"; VENDOR_ID=$(echo "$LAST" | body | json id)
STORE="Cart Mart $RUN"
call PUT /api/vendors/me "$VTOKEN" '{"businessName":"'"$STORE"'","legalName":"Cart Mart Pvt Ltd","gstin":"29ABCDE1234F1Z5","fssaiLicense":"12345678901234","contactEmail":"ops@cart.in","contactPhone":"+919876543210","address":{"line1":"4 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.97,"longitude":77.59}}' >/dev/null
call POST /api/vendors/me/submit "$VTOKEN" >/dev/null
call POST /api/admin/vendors/$VENDOR_ID/approve "$ATOKEN" >/dev/null
for _ in $(seq 1 15); do VTOKEN=$(refresh "$VJAR"); claims "$VTOKEN" | grep -q "\"vendor_id\":\"$VENDOR_ID\"" && break; sleep 1; done
claims "$VTOKEN" | grep -q "\"vendor_id\":\"$VENDOR_ID\"" && ok "vendor approved (vendor_id claim)" || bad "vendor_id claim" "$(claims "$VTOKEN")"
RICE=$(call POST /api/vendor/products "$VTOKEN" '{"name":"Sona Masoori Rice","categoryId":"'"$CAT"'","unitValue":1,"unitOfMeasure":"KG","foodType":"VEG"}' | body | json id)
DAL=$(call POST /api/vendor/products "$VTOKEN" '{"name":"Moong Dal","categoryId":"'"$CAT"'","unitValue":500,"unitOfMeasure":"G","foodType":"VEG"}' | body | json id)
call POST /api/vendor/products/$RICE/publish "$VTOKEN" >/dev/null; call POST /api/vendor/products/$DAL/publish "$VTOKEN" >/dev/null
poll "pricing knows the products" 15 '^200 ' GET /api/vendor/prices/$DAL "$VTOKEN"
poll "inventory knows the products" 15 '^200 ' GET /api/vendor/inventory/$DAL "$VTOKEN"
call PUT /api/vendor/prices/$RICE "$VTOKEN" '{"mrp":60.00,"sellingPrice":50.00}' >/dev/null
call PUT /api/vendor/prices/$DAL "$VTOKEN" '{"mrp":80.00,"sellingPrice":72.00}' >/dev/null
call POST /api/vendor/inventory/$RICE/adjustments "$VTOKEN" '{"type":"SET","quantity":5,"reason":"STOCK_COUNT"}' >/dev/null
call POST /api/vendor/inventory/$DAL/adjustments "$VTOKEN" '{"type":"SET","quantity":40,"reason":"STOCK_COUNT"}' >/dev/null
poll "products searchable with price and stock" 15 '"sellingPrice":72.*"inStock":true|"inStock":true.*"sellingPrice":72' GET /api/catalog/products/$DAL

echo "== Setup: an approved restaurant (open all day) with a menu"
RJAR="$(mktemp)"; RTOKEN=$(register "rest5-$RUN@example.com" RESTAURANT_OWNER "Cart Chef" "$RJAR")
poll "restaurant draft created" 15 '^200 ' GET /api/restaurants/me "$RTOKEN"; REST_ID=$(echo "$LAST" | body | json id)
HOURS=$(for d in MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY SUNDAY; do printf '{"dayOfWeek":"%s","opensAt":"00:00","closesAt":"23:59"},' $d; done)
call PUT /api/restaurants/me "$RTOKEN" '{"name":"Cart Kitchen '"$RUN"'","cuisines":["SOUTH_INDIAN"],"fssaiLicense":"12345678901234","contactEmail":"k@cart.in","contactPhone":"+919876543210","address":{"line1":"5 CMH Road","city":"Bengaluru","state":"Karnataka","postalCode":"560038","country":"IN","latitude":12.9784,"longitude":77.6408},"openingHours":['"${HOURS%,}"']}' >/dev/null
MCAT=$(call POST /api/restaurant/menu/categories "$RTOKEN" '{"name":"Dosa"}' | body | json id)
DOSA=$(call POST /api/restaurant/menu/items "$RTOKEN" '{"categoryId":"'"$MCAT"'","name":"Masala Dosa","price":90.00,"foodType":"VEG"}' | body | json id)
call POST /api/restaurants/me/submit "$RTOKEN" >/dev/null
expect "approve restaurant" 200 "$(call POST /api/admin/restaurants/$REST_ID/approve "$ATOKEN")"
for _ in $(seq 1 15); do RTOKEN=$(refresh "$RJAR"); claims "$RTOKEN" | grep -q "\"restaurant_id\":\"$REST_ID\"" && break; sleep 1; done

echo "== Setup: a customer with addresses"
CEMAIL="cust5-$RUN@example.com"; CTOKEN=$(register "$CEMAIL" CUSTOMER "Cart Shopper")
poll "customer profile created" 15 '^200 ' GET /api/customers/me "$CTOKEN"
addr() { call POST /api/customers/me/addresses "$CTOKEN" '{"label":"'"$1"'","recipientName":"Cart Shopper","phone":"+919876543210","line1":"1 Test Street","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN"'"$2"'}' | body | json id; }
NEAR=$(addr Home ',"latitude":12.975,"longitude":77.60')
MID=$(addr Office ',"latitude":12.97,"longitude":77.65')
FAR=$(addr Farm ',"latitude":13.5,"longitude":78.5')
NOLOC=$(addr Hostel '')
[ -n "$NEAR" ] && [ -n "$MID" ] && [ -n "$FAR" ] && [ -n "$NOLOC" ] && ok "4 addresses (first = default)" || bad "addresses" "$NEAR $MID $FAR $NOLOC"

echo "== Cart basics and rules"
has "empty cart" '"store":null' "$(call GET /api/cart "$CTOKEN")"
expect "guest has no cart" 401 "$(call GET /api/cart)"
expect "vendors have no cart" 403 "$(call GET /api/cart "$VTOKEN")"
r=$(call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$RICE"'","quantity":2}'); expect "add rice ×2" 200 "$r"
has "store and totals (subtotal 100, savings 20)" "\"name\":\"$STORE\".*\"subtotal\":100(\.0+)?,.*\"savings\":20" "$r"
r=$(call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$RICE"'","quantity":4}'); expect "6 rice > 5 in stock" 409 "$r"
has "INSUFFICIENT_STOCK with available=5" '"errorCode":"INSUFFICIENT_STOCK".*"available":5|"available":5.*"errorCode":"INSUFFICIENT_STOCK"' "$r"
expect "quantity limit on add" 422 "$(call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$DAL"'","quantity":21}')"
r=$(call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$DAL"'","quantity":1}'); expect "add dal ×1" 200 "$r"
has "subtotal 172, 3 items" '"itemCount":3.*"subtotal":172|"subtotal":172.*"itemCount":3' "$r"
expect "21 per line is over the limit" 422 "$(call PUT /api/cart/items/$DAL "$CTOKEN" '{"quantity":21}')"
r=$(call POST /api/cart/items "$CTOKEN" '{"type":"MENU_ITEM","restaurantId":"'"$REST_ID"'","menuItemId":"'"$DOSA"'","quantity":1}'); expect "item from another store" 409 "$r"
has "conflict names the current store" "\"errorCode\":\"CART_STORE_CONFLICT\".*$STORE|$STORE.*\"errorCode\":\"CART_STORE_CONFLICT\"" "$r"
CTOKEN2=$(login "$CEMAIL" Secret123)
has "cart persists across logins" "\"itemCount\":3" "$(call GET /api/cart "$CTOKEN2")"

echo "== Checkout preview — delivery fee, minimum order, delivery area"
r=$(call GET /api/cart/preview "$CTOKEN"); expect "preview (default address)" 200 "$r"
has "default address used, 1.6 km, fee ₹25, total ₹197, can checkout" "\"distanceKm\":1.6.*\"fee\":25(\.0+)?,.*\"total\":197(\.0+)?,.*\"canCheckout\":true" "$r"
has "extra-km fee: 8.5 km → ₹73" '"distanceKm":8.5.*"fee":73' "$(call GET "/api/cart/preview?addressId=$MID" "$CTOKEN")"
has "far address is out of the delivery area" '"OUT_OF_DELIVERY_AREA".*"canCheckout":false' "$(call GET "/api/cart/preview?addressId=$FAR" "$CTOKEN")"
has "address without location is blocked" '"ADDRESS_LOCATION_MISSING"' "$(call GET "/api/cart/preview?addressId=$NOLOC" "$CTOKEN")"
OTHER=$(register "other5-$RUN@example.com" CUSTOMER "Other Shopper")
expect "another customer's address is not found" 404 "$(call GET "/api/cart/preview?addressId=$NEAR" "$OTHER")"
expect "remove rice" 200 "$(call DELETE /api/cart/items/$RICE "$CTOKEN")"
has "₹72 is below the ₹99 minimum order" '"BELOW_MINIMUM_ORDER".*"canCheckout":false' "$(call GET /api/cart/preview "$CTOKEN")"
expect "dal ×7" 200 "$(call PUT /api/cart/items/$DAL "$CTOKEN" '{"quantity":7}')"
has "₹504 ≥ ₹499 → free delivery" '"fee":0(\.0+)?,.*"total":504' "$(call GET /api/cart/preview "$CTOKEN")"

echo "== Acceptance: the preview uses current prices; unavailable items are flagged"
call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$RICE"'","quantity":2}' >/dev/null
expect "vendor lowers rice to ₹45 (−10%, applies now)" 200 "$(call PUT /api/vendor/prices/$RICE "$VTOKEN" '{"mrp":60.00,"sellingPrice":45.00}')"
poll "cart shows the new price with PRICE_CHANGED (not blocking)" 10 '"PRICE_CHANGED"' GET /api/cart/preview "$CTOKEN"
has "rice now ₹45 × 2 in the subtotal (504 + 90 = 594)" '"subtotal":594' "$LAST"
has "price change alone does not block checkout" '"canCheckout":true' "$LAST"
call POST /api/vendor/inventory/$RICE/adjustments "$VTOKEN" '{"type":"SET","quantity":1,"reason":"STOCK_COUNT"}' >/dev/null
poll "stock fell to 1 → INSUFFICIENT_STOCK blocks checkout" 5 '"INSUFFICIENT_STOCK".*"canCheckout":false' GET /api/cart/preview "$CTOKEN"
call POST /api/vendor/inventory/$RICE/adjustments "$VTOKEN" '{"type":"SET","quantity":0,"reason":"STOCK_COUNT"}' >/dev/null
poll "stock 0 → OUT_OF_STOCK" 5 '"OUT_OF_STOCK"' GET /api/cart "$CTOKEN"
call POST /api/vendor/inventory/$RICE/adjustments "$VTOKEN" '{"type":"SET","quantity":10,"reason":"RESTOCK"}' >/dev/null
call POST /api/vendor/products/$RICE/unpublish "$VTOKEN" >/dev/null
poll "unpublished product → ITEM_UNAVAILABLE" 10 '"ITEM_UNAVAILABLE"' GET /api/cart "$CTOKEN"
call POST /api/vendor/products/$RICE/publish "$VTOKEN" >/dev/null
poll "republished → can check out again" 10 '"canCheckout":true' GET /api/cart/preview "$CTOKEN"

echo "== Two tabs at once: no lost updates"
sleep 2
rm -f "$PAR"/*
for i in $(seq 1 10); do curl -s -o /dev/null -w '%{http_code}\n' -X POST "$GW/api/cart/items" -H "Authorization: Bearer $CTOKEN" -H 'Content-Type: application/json' -d '{"type":"PRODUCT","productId":"'"$DAL"'","quantity":1}' > "$PAR/$i" & done; wait
statuses=$(cat "$PAR"/* | sort | uniq -c | tr -s ' ' | tr '\n' ';'); okCount=$(cat "$PAR"/* | grep -c '^200$')
echo "    10 parallel adds → $statuses"
r=$(call GET /api/cart "$CTOKEN"); q=$(echo "$r" | grep -o "\"itemId\":\"$DAL\"[^}]*\"quantity\":[0-9]*" | grep -o '"quantity":[0-9]*' | cut -d: -f2)
# Every successful add must be counted exactly once; a refused one (409 CONCURRENT_MODIFICATION after retries) changes nothing.
[ "$q" = "$((7 + okCount))" ] && ! cat "$PAR"/* | grep -qvE '^(200|409)$' && [ "$okCount" -ge 1 ] \
  && ok "no lost updates: 7 + $okCount successful adds = $q" || bad "parallel adds (7 + $okCount ≠ $q)" "$statuses $r"

echo "== Restaurant cart"
r=$(call POST /api/cart/items "$CTOKEN" '{"type":"MENU_ITEM","restaurantId":"'"$REST_ID"'","menuItemId":"'"$DOSA"'","quantity":2,"replaceCart":true}'); expect "start a new cart at the restaurant (replaceCart)" 200 "$r"
has "restaurant cart: 2 × ₹90" '"type":"RESTAURANT".*"subtotal":180|"subtotal":180.*"type":"RESTAURANT"' "$r"
has "restaurant delivery: 5.8 km → ₹60" '"distanceKm":5.8.*"fee":60' "$(call GET /api/cart/preview "$CTOKEN")"
expect "restaurant pauses orders" 200 "$(call PUT /api/restaurant/accepting-orders "$RTOKEN" '{"acceptingOrders":false}')"
has "NOT_ACCEPTING_ORDERS blocks checkout" '"NOT_ACCEPTING_ORDERS".*"canCheckout":false' "$(call GET /api/cart/preview "$CTOKEN")"
expect "cannot add while orders are paused" 409 "$(call POST /api/cart/items "$CTOKEN" '{"type":"MENU_ITEM","restaurantId":"'"$REST_ID"'","menuItemId":"'"$DOSA"'","quantity":1}')"
call PUT /api/restaurant/accepting-orders "$RTOKEN" '{"acceptingOrders":true}' >/dev/null
expect "dosa sold out" 200 "$(call PATCH /api/restaurant/menu/items/$DOSA/availability "$RTOKEN" '{"available":false}')"
has "sold-out dish → ITEM_UNAVAILABLE" '"ITEM_UNAVAILABLE"' "$(call GET /api/cart "$CTOKEN")"
expect "clear cart" 204 "$(call DELETE /api/cart "$CTOKEN")"
has "cart is empty" '"store":null' "$(call GET /api/cart "$CTOKEN")"

echo "== Internal endpoints stay internal"
expect "/internal/** is not routed by the gateway" 404 "$(call POST /internal/inventory/availability "$CTOKEN" '{"productIds":["'"$DAL"'"]}')"

rm -rf "$VJAR" "$RJAR" "$PAR"
echo
echo "Passed: $PASS   Failed: $FAIL"
[ "$FAIL" -eq 0 ]
