#!/usr/bin/env bash
# =====================================================================================
# Phase 3 end-to-end smoke test — catalog, menus and search, everything through the API gateway.
# Prereq: cd infrastructure/local && docker compose --profile core --profile catalog up -d --build
# Run (Git Bash):  bash infrastructure/local/scripts/smoke-phase3.sh
# Exit code 0 = every check passed. Safe to re-run: every run uses its own names.
# Note: one step deletes the products search index on purpose and rebuilds it with "republish".
# =====================================================================================
set -uo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
ES="${ELASTICSEARCH_URL:-http://localhost:9200}"
ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-admin@grocery.local}"
ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-Admin@12345}"
RUN="$(date +%s)"
TAG="$(echo "$RUN" | tr '0-9' 'A-J')"        # letters only (cuisine tokens allow A-Z and _)
LAT=12.9716; LNG=77.5946                      # Bengaluru
PASS=0; FAIL=0

ok()   { echo "  ✔ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ✘ $1"; echo "    → $2" | head -c 600; echo; FAIL=$((FAIL+1)); }
json() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }   # FIRST matching string field (top-level id, not a nested one)
claims() { local p; p=$(echo "$1" | cut -d. -f2 | tr '_-' '/+'); while [ $(( ${#p} % 4 )) -ne 0 ]; do p="$p="; done; echo "$p" | base64 -d 2>/dev/null; }
call() {  # method path token [body] -> prints "STATUS BODY"
  local method=$1 path=$2 token=${3:-} body=${4:-}
  local args=(-s -o /tmp/smoke3_body -w '%{http_code}' -X "$method" "$GW$path" -H "X-Correlation-Id: smoke3-$RUN-$RANDOM")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local status; status=$(curl "${args[@]}"); echo "$status $(cat /tmp/smoke3_body)"
}
body() { cut -d' ' -f2-; }
expect() { local status=${3%% *}; if [ "$status" = "$2" ]; then ok "$1 ($status)"; else bad "$1 (expected $2, got $status)" "${3#* }"; fi; }
# poll "label" maxSeconds "pattern" method path [token] — repeats the call until the response matches pattern
poll() {
  local label=$1 max=$2 pattern=$3 method=$4 path=$5 token=${6:-} start r elapsed
  start=$(date +%s%N)
  for _ in $(seq 1 $((max * 4))); do
    r=$(call "$method" "$path" "$token")
    if echo "$r" | grep -Eq -- "$pattern"; then
      elapsed=$(( ($(date +%s%N) - start) / 1000000 )); ok "$label (${elapsed} ms)"; LAST="$r"; return 0
    fi
    sleep 0.25
  done
  bad "$label" "$r"; LAST="$r"; return 1
}
register() { # email role fullName -> access token (cookie jar in $2-jar when given)
  curl -s ${4:+-c "$4"} -X POST "$GW/api/auth/register" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"Secret123\",\"fullName\":\"$3\",\"phone\":\"+919876543210\",\"role\":\"$2\"}" | json accessToken
}
refresh() { curl -s -b "$1" -c "$1" -X POST "$GW/api/auth/refresh" -H 'X-Requested-With: XMLHttpRequest' | json accessToken; }

echo "== Health (public)"
for s in gateway catalog-command catalog-query restaurant; do expect "health/$s" 200 "$(call GET /api/health/$s)"; done

ATOKEN=$(call POST /api/auth/login "" "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | body | json accessToken)
[ -n "$ATOKEN" ] && ok "admin login" || bad "admin login" "no token"

echo "== Admin builds the category tree"
r=$(call POST /api/admin/categories "$ATOKEN" "{\"name\":\"Smoke Produce $RUN\",\"sortOrder\":1}"); expect "create top-level category" 201 "$r"
ROOT_ID=$(echo "$r" | body | json id)
r=$(call POST /api/admin/categories "$ATOKEN" "{\"name\":\"Fresh Vegetables\",\"parentId\":\"$ROOT_ID\"}"); expect "create sub-category" 201 "$r"
VEG_ID=$(echo "$r" | body | json id)
expect "sibling name taken (case-insensitive)" 409 "$(call POST /api/admin/categories "$ATOKEN" "{\"name\":\"fresh vegetables\",\"parentId\":\"$ROOT_ID\"}")"
r=$(call POST /api/admin/categories "$ATOKEN" "{\"name\":\"Dairy\",\"parentId\":\"$ROOT_ID\"}"); DAIRY_ID=$(echo "$r" | body | json id)
poll "category tree is public and shows the new category" 10 "$ROOT_ID" GET /api/catalog/categories

echo "== Vendor gets approved (Phase 2 flow) → vendor_id claim"
VJAR="$(mktemp)"
VTOKEN=$(register "vendor3-$RUN@example.com" VENDOR "Smoke Grocer" "$VJAR")
poll "vendor draft created from the registration event" 15 '^200 ' GET /api/vendors/me "$VTOKEN"
VENDOR_ID=$(echo "$LAST" | body | json id)
expect "vendor without approval cannot add products" 403 "$(call POST /api/vendor/products "$VTOKEN" '{"name":"x","categoryId":"'"$VEG_ID"'","unitValue":1,"unitOfMeasure":"KG"}')"
call PUT /api/vendors/me "$VTOKEN" '{"businessName":"Green Basket '"$RUN"'","legalName":"Green Basket Pvt Ltd","gstin":"29ABCDE1234F1Z5","fssaiLicense":"12345678901234","contactEmail":"ops@green.in","contactPhone":"+919876543210","address":{"line1":"1 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.97,"longitude":77.59}}' >/dev/null
call POST /api/vendors/me/submit "$VTOKEN" >/dev/null
expect "admin approves vendor" 200 "$(call POST /api/admin/vendors/$VENDOR_ID/approve "$ATOKEN")"
for _ in $(seq 1 15); do VTOKEN=$(refresh "$VJAR"); claims "$VTOKEN" | grep -q "\"vendor_id\":\"$VENDOR_ID\"" && break; sleep 1; done
claims "$VTOKEN" | grep -q "\"vendor_id\":\"$VENDOR_ID\"" && ok "token carries vendor_id" || bad "vendor_id claim" "$(claims "$VTOKEN")"

echo "== Vendor lists products"
r=$(call POST /api/vendor/products "$VTOKEN" '{"name":"Tomato","description":"Farm-fresh red tomatoes","categoryId":"'"$VEG_ID"'","brandName":"Farm Fresh '"$TAG"'","unitValue":500,"unitOfMeasure":"G","sku":"TOM-500","foodType":"VEG","tags":["Fresh","Local"],"attributes":[{"name":"Origin","value":"Nashik"}],"imageUrls":["https://images.example.com/tomato.jpg"]}')
expect "create product (DRAFT)" 201 "$r"; TOMATO_ID=$(echo "$r" | body | json id)
echo "$r" | grep -q '"unitLabel":"500 g"' && ok "unit label formatted (500 g)" || bad "unit label" "$r"
expect "duplicate SKU rejected" 409 "$(call POST /api/vendor/products "$VTOKEN" '{"name":"Tomato 2","categoryId":"'"$VEG_ID"'","unitValue":1,"unitOfMeasure":"KG","sku":"TOM-500"}')"
r=$(call POST /api/vendor/products "$VTOKEN" '{"name":"Curd","description":"Thick set curd","categoryId":"'"$DAIRY_ID"'","brandName":"farm  fresh '"$TAG"'","unitValue":400,"unitOfMeasure":"G","foodType":"VEG"}')
CURD_ID=$(echo "$r" | body | json id)
echo "$r" | grep -q "\"name\":\"Farm Fresh $TAG\"" && ok "brand matched case-insensitively" || bad "brand reuse" "$r"
expect "draft is not visible to customers" 404 "$(call GET /api/catalog/products/$TOMATO_ID)"
expect "publish tomato" 200 "$(call POST /api/vendor/products/$TOMATO_ID/publish "$VTOKEN")"
call POST /api/vendor/products/$CURD_ID/publish "$VTOKEN" >/dev/null

echo "== Search (anonymous) — acceptance: searchable within ~2 s, typo tolerant, synonyms"
poll "new product searchable" 10 "$TOMATO_ID" GET "/api/search/products?q=tomato&vendorId=$VENDOR_ID"
poll "typo: 'tomatos' finds Tomato" 5 "$TOMATO_ID" GET "/api/search/products?q=tomatos&vendorId=$VENDOR_ID"
poll "synonym: 'yogurt' finds Curd" 10 "$CURD_ID" GET "/api/search/products?q=yogurt&vendorId=$VENDOR_ID"
poll "parent category filter includes sub-categories" 5 "$TOMATO_ID" GET "/api/search/products?categoryId=$ROOT_ID&vendorId=$VENDOR_ID"
echo "$LAST" | grep -q '"facets"' && ok "facets returned" || bad "facets" "$LAST"
poll "autocomplete suggests 'toma…'" 5 "$TOMATO_ID" GET "/api/search/suggest?q=toma"
expect "product detail" 200 "$(call GET /api/catalog/products/$TOMATO_ID)"
expect "guest cannot write" 401 "$(call POST /api/vendor/products "" '{}')"

echo "== Edits and visibility"
expect "edit published product" 200 "$(call PUT /api/vendor/products/$TOMATO_ID "$VTOKEN" '{"name":"Tomato Hybrid","description":"Farm-fresh red tomatoes","categoryId":"'"$VEG_ID"'","brandName":"Farm Fresh '"$TAG"'","unitValue":1,"unitOfMeasure":"KG","sku":"TOM-500","foodType":"VEG"}')"
poll "edit reaches search (name + unit)" 10 '"name":"Tomato Hybrid".*"unitLabel":"1 kg"' GET /api/catalog/products/$TOMATO_ID
expect "unpublish" 200 "$(call POST /api/vendor/products/$TOMATO_ID/unpublish "$VTOKEN")"
poll "unpublished product disappears" 10 '^404 ' GET /api/catalog/products/$TOMATO_ID
call POST /api/vendor/products/$TOMATO_ID/publish "$VTOKEN" >/dev/null
poll "published again" 10 '^200 ' GET /api/catalog/products/$TOMATO_ID

echo "== Acceptance: a suspended vendor's products disappear from search"
expect "suspend vendor" 200 "$(call POST /api/admin/vendors/$VENDOR_ID/suspend "$ATOKEN" '{"reason":"smoke test"}')"
poll "products hidden after suspension" 15 '"totalElements":0' GET "/api/search/products?vendorId=$VENDOR_ID"
expect "reinstate vendor" 200 "$(call POST /api/admin/vendors/$VENDOR_ID/reinstate "$ATOKEN")"
poll "products visible again after reinstatement" 15 '"totalElements":2' GET "/api/search/products?vendorId=$VENDOR_ID"

echo "== Category deactivation hides its products"
expect "deactivate Dairy" 200 "$(call POST /api/admin/categories/$DAIRY_ID/deactivate "$ATOKEN")"
poll "curd hidden with its category" 10 '^404 ' GET /api/catalog/products/$CURD_ID
expect "activate Dairy" 200 "$(call POST /api/admin/categories/$DAIRY_ID/activate "$ATOKEN")"
poll "curd visible again" 10 '^200 ' GET /api/catalog/products/$CURD_ID
expect "category in use cannot be deleted" 409 "$(call DELETE /api/admin/categories/$DAIRY_ID "$ATOKEN")"

echo "== Restaurant menu (built before approval) → live after approval"
RJAR="$(mktemp)"
RTOKEN=$(register "rest3-$RUN@example.com" RESTAURANT_OWNER "Smoke Chef" "$RJAR")
poll "restaurant draft created" 15 '^200 ' GET /api/restaurants/me "$RTOKEN"
REST_ID=$(echo "$LAST" | body | json id)
HOURS=$(for d in MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY SUNDAY; do printf '{"dayOfWeek":"%s","opensAt":"00:00","closesAt":"23:59"},' $d; done)
expect "save restaurant profile" 200 "$(call PUT /api/restaurants/me "$RTOKEN" '{"name":"Spice Route '"$RUN"'","description":"North Indian kitchen","cuisines":["NORTH_INDIAN","SMOKE_'"$TAG"'"],"fssaiLicense":"12345678901234","contactEmail":"k@spice.in","contactPhone":"+919876543210","address":{"line1":"2 MG Road","line2":"Indiranagar","city":"Bengaluru","state":"Karnataka","postalCode":"560038","country":"IN","latitude":12.9784,"longitude":77.6408},"openingHours":['"${HOURS%,}"']}')"
r=$(call POST /api/restaurant/menu/categories "$RTOKEN" '{"name":"Biryani","sortOrder":1}'); expect "add menu category" 201 "$r"
MCAT_ID=$(echo "$r" | body | json id)
r=$(call POST /api/restaurant/menu/items "$RTOKEN" '{"categoryId":"'"$MCAT_ID"'","name":"Chicken Biryani","description":"Dum-cooked","price":249.00,"foodType":"NON_VEG"}'); expect "add menu item" 201 "$r"
ITEM_ID=$(echo "$r" | body | json id)
expect "add veg item" 201 "$(call POST /api/restaurant/menu/items "$RTOKEN" '{"categoryId":"'"$MCAT_ID"'","name":"Veg Biryani","price":199.00,"foodType":"VEG"}')"
expect "non-empty menu category cannot be deleted" 409 "$(call DELETE /api/restaurant/menu/categories/$MCAT_ID "$RTOKEN")"
expect "not visible before approval" 404 "$(call GET /api/catalog/restaurants/$REST_ID)"
call POST /api/restaurants/me/submit "$RTOKEN" >/dev/null
expect "admin reviews the menu" 200 "$(call GET /api/admin/restaurants/$REST_ID/menu "$ATOKEN")"
expect "approve restaurant" 200 "$(call POST /api/admin/restaurants/$REST_ID/approve "$ATOKEN")"
# Approval changes the owner's claims: identity revokes older tokens, so refresh into one with restaurant_id.
for _ in $(seq 1 15); do RTOKEN=$(refresh "$RJAR"); claims "$RTOKEN" | grep -q "\"restaurant_id\":\"$REST_ID\"" && break; sleep 1; done
claims "$RTOKEN" | grep -q "\"restaurant_id\":\"$REST_ID\"" && ok "owner token refreshed with restaurant_id" || bad "restaurant_id claim" "$(claims "$RTOKEN")"
NEAR="lat=$LAT&lng=$LNG&cuisine=SMOKE_$TAG"
poll "dish search 'biryani' near me finds the restaurant" 10 "Chicken Biryani" GET "/api/search/restaurants?$NEAR&q=biryani"
echo "$LAST" | grep -q '"distanceKm":[0-9]' && ok "distance returned" || bad "distanceKm" "$LAST"
echo "$LAST" | grep -q '"openNow":true' && ok "open now (hours cover the whole day)" || bad "openNow" "$LAST"
poll "pure-veg filter excludes it" 5 '"totalElements":0' GET "/api/search/restaurants?$NEAR&pureVeg=true"
poll "restaurant page with menu" 5 'Veg Biryani' GET /api/catalog/restaurants/$REST_ID
expect "mark item sold out" 200 "$(call PATCH /api/restaurant/menu/items/$ITEM_ID/availability "$RTOKEN" '{"available":false}')"
poll "sold-out item shown as unavailable" 10 '"name":"Chicken Biryani"[^}]*"available":false' GET /api/catalog/restaurants/$REST_ID
expect "pause orders" 200 "$(call PUT /api/restaurant/accepting-orders "$RTOKEN" '{"acceptingOrders":false}')"
poll "listing shows not accepting orders" 10 '"acceptingOrders":false' GET /api/catalog/restaurants/$REST_ID
expect "suspend restaurant" 200 "$(call POST /api/admin/restaurants/$REST_ID/suspend "$ATOKEN" '{"reason":"smoke test"}')"
poll "suspended restaurant hidden" 10 '^404 ' GET /api/catalog/restaurants/$REST_ID
# Suspension revokes the owner's tokens again; a refreshed token (without restaurant_id) still reaches the menu API.
for _ in $(seq 1 15); do RTOKEN=$(refresh "$RJAR"); r=$(call PATCH /api/restaurant/menu/items/$ITEM_ID/availability "$RTOKEN" '{"available":true}'); [ "${r%% *}" != "401" ] && break; sleep 1; done
expect "menu locked while suspended" 409 "$r"
call POST /api/admin/restaurants/$REST_ID/reinstate "$ATOKEN" >/dev/null

echo "== Acceptance: after deleting the index, republish rebuilds it"
CONCRETE=$(curl -s "$ES/_alias/products" | sed -n 's/^{"\([^"]*\)".*/\1/p')
[ -n "$CONCRETE" ] && ok "products alias → $CONCRETE" || bad "products alias" "$(curl -s "$ES/_alias/products")"
curl -s -o /dev/null -X DELETE "$ES/$CONCRETE"
poll "index gone → product not found" 5 '"totalElements":0' GET "/api/search/products?vendorId=$VENDOR_ID"
r=$(call POST /api/admin/catalog/republish "$ATOKEN"); expect "admin republishes the catalog" 200 "$r"
poll "index rebuilt from MySQL via events" 20 "$TOMATO_ID" GET "/api/search/products?q=tomato&vendorId=$VENDOR_ID"
expect "restaurant listings republish" 200 "$(call POST /api/admin/restaurants/listings/republish "$ATOKEN")"
expect "admin search index status" 200 "$(call GET /api/admin/search/indexes "$ATOKEN")"

echo "== Permissions"
CTOKEN=$(register "cust3-$RUN@example.com" CUSTOMER "Smoke Shopper")
expect "customer cannot manage products" 403 "$(call GET /api/vendor/products "$CTOKEN")"
expect "customer cannot manage categories" 403 "$(call POST /api/admin/categories "$CTOKEN" '{"name":"x"}')"
expect "customer searches while logged in" 200 "$(call GET "/api/search/products?q=tomato" "$CTOKEN")"

rm -f "$VJAR" "$RJAR"
echo
echo "Passed: $PASS   Failed: $FAIL"
[ "$FAIL" -eq 0 ]
