#!/usr/bin/env bash
# =====================================================================================
# Phase 4 end-to-end smoke test — inventory, pricing and price/stock in search, through the API gateway.
# Prereq: cd infrastructure/local && docker compose --profile core --profile catalog --profile inventory up -d --build
# Run (Git Bash):  bash infrastructure/local/scripts/smoke-phase4.sh
# Exit code 0 = every check passed. Safe to re-run: every run uses its own vendor and names.
# Note: one step deletes the products search index on purpose and rebuilds it with the three republish calls.
# =====================================================================================
set -uo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
ES="${ELASTICSEARCH_URL:-http://localhost:9200}"
ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-admin@grocery.local}"
ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-Admin@12345}"
RUN="$(date +%s)"
PASS=0; FAIL=0
PAR="$(mktemp -d)"

ok()   { echo "  ✔ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ✘ $1"; echo "    → $2" | head -c 700; echo; FAIL=$((FAIL+1)); }
json() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }        # first string field
num()  { grep -o "\"$1\":-\?[0-9.]*" | head -1 | cut -d: -f2; }          # first numeric field
claims() { local p; p=$(echo "$1" | cut -d. -f2 | tr '_-' '/+'); while [ $(( ${#p} % 4 )) -ne 0 ]; do p="$p="; done; echo "$p" | base64 -d 2>/dev/null; }
call() {  # method path token [body] -> prints "STATUS BODY"
  local method=$1 path=$2 token=${3:-} body=${4:-}
  local args=(-s -o /tmp/smoke4_body -w '%{http_code}' -X "$method" "$GW$path" -H "X-Correlation-Id: smoke4-$RUN-$RANDOM")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local status; status=$(curl "${args[@]}"); echo "$status $(cat /tmp/smoke4_body)"
}
body() { cut -d' ' -f2-; }
expect() { local status=${3%% *}; if [ "$status" = "$2" ]; then ok "$1 ($status)"; else bad "$1 (expected $2, got $status)" "${3#* }"; fi; }
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
parallel() { # n method path token body -> "<count> <status>" lines
  local n=$1 method=$2 path=$3 token=$4 data=$5
  rm -f "$PAR"/*
  for i in $(seq 1 "$n"); do
    curl -s -o /dev/null -w '%{http_code}\n' -X "$method" "$GW$path" -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' -d "$data" > "$PAR/$i" &
  done
  wait
  cat "$PAR"/* | sort | uniq -c
}
register() { curl -s ${4:+-c "$4"} -X POST "$GW/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"Secret123\",\"fullName\":\"$3\",\"phone\":\"+919876543210\",\"role\":\"$2\"}" | json accessToken; }
refresh() { curl -s -b "$1" -c "$1" -X POST "$GW/api/auth/refresh" -H 'X-Requested-With: XMLHttpRequest' | json accessToken; }
adjust() { call POST "/api/vendor/inventory/$1/adjustments" "$VTOKEN" "{\"type\":\"$2\",\"quantity\":$3,\"reason\":\"$4\"}"; }

echo "== Health (public)"
for s in gateway inventory pricing catalog-command catalog-query; do expect "health/$s" 200 "$(call GET /api/health/$s)"; done
ATOKEN=$(call POST /api/auth/login "" "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | body | json accessToken)
[ -n "$ATOKEN" ] && ok "admin login" || bad "admin login" "no token"

echo "== Setup: category, approved vendor, two published products"
CAT_ID=$(call POST /api/admin/categories "$ATOKEN" "{\"name\":\"Smoke Staples $RUN\"}" | body | json id)
[ -n "$CAT_ID" ] && ok "category created" || bad "category" "no id"
VJAR="$(mktemp)"
VTOKEN=$(register "vendor4-$RUN@example.com" VENDOR "Smoke Wholesaler" "$VJAR")
poll "vendor draft created" 15 '^200 ' GET /api/vendors/me "$VTOKEN"
VENDOR_ID=$(echo "$LAST" | body | json id)
call PUT /api/vendors/me "$VTOKEN" '{"businessName":"Staples Hub '"$RUN"'","legalName":"Staples Hub Pvt Ltd","gstin":"29ABCDE1234F1Z5","fssaiLicense":"12345678901234","contactEmail":"ops@staples.in","contactPhone":"+919876543210","address":{"line1":"3 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.97,"longitude":77.59}}' >/dev/null
call POST /api/vendors/me/submit "$VTOKEN" >/dev/null
expect "admin approves vendor" 200 "$(call POST /api/admin/vendors/$VENDOR_ID/approve "$ATOKEN")"
for _ in $(seq 1 15); do VTOKEN=$(refresh "$VJAR"); claims "$VTOKEN" | grep -q "\"vendor_id\":\"$VENDOR_ID\"" && break; sleep 1; done
claims "$VTOKEN" | grep -q "\"vendor_id\":\"$VENDOR_ID\"" && ok "token carries vendor_id" || bad "vendor_id claim" "$(claims "$VTOKEN")"
RICE=$(call POST /api/vendor/products "$VTOKEN" '{"name":"Basmati Rice","categoryId":"'"$CAT_ID"'","unitValue":1,"unitOfMeasure":"KG","sku":"RICE-1","foodType":"VEG"}' | body | json id)
DAL=$(call POST /api/vendor/products "$VTOKEN" '{"name":"Toor Dal","categoryId":"'"$CAT_ID"'","unitValue":500,"unitOfMeasure":"G","sku":"DAL-500","foodType":"VEG"}' | body | json id)
call POST /api/vendor/products/$RICE/publish "$VTOKEN" >/dev/null; call POST /api/vendor/products/$DAL/publish "$VTOKEN" >/dev/null
poll "inventory learns the product from catalog.events (stock 0)" 15 '"available":0' GET /api/vendor/inventory/$RICE "$VTOKEN"
poll "pricing learns the product (no price yet)" 15 '"price":null' GET /api/vendor/prices/$RICE "$VTOKEN"
poll "search shows it without price, out of stock" 15 '"price":null.*"inStock":false|"inStock":false.*"price":null' GET /api/catalog/products/$RICE

echo "== Pricing — acceptance: a change above the threshold waits for admin approval"
r=$(call PUT /api/vendor/prices/$RICE "$VTOKEN" '{"mrp":120.00,"sellingPrice":100.00}'); expect "first price applies immediately" 200 "$r"
echo "$r" | grep -q '"outcome":"APPLIED"' && ok "outcome APPLIED" || bad "outcome" "$r"
poll "search shows ₹100, 17% off" 10 '"sellingPrice":100(\.0+)?,.*"discountPercent":17|"discountPercent":17.*"sellingPrice":100' GET /api/catalog/products/$RICE
expect "selling price above MRP rejected" 422 "$(call PUT /api/vendor/prices/$RICE "$VTOKEN" '{"mrp":80.00,"sellingPrice":90.00}')"
expect "−10% applies immediately" 200 "$(call PUT /api/vendor/prices/$RICE "$VTOKEN" '{"mrp":120.00,"sellingPrice":90.00}')"
poll "search shows ₹90" 10 '"sellingPrice":90' GET /api/catalog/products/$RICE
r=$(call PUT /api/vendor/prices/$RICE "$VTOKEN" '{"mrp":120.00,"sellingPrice":50.00,"note":"Clearance"}'); expect "−44% needs approval" 202 "$r"
echo "$r" | grep -q '"outcome":"PENDING_APPROVAL"' && ok "outcome PENDING_APPROVAL" || bad "outcome" "$r"
REQ_ID=$(echo "$r" | body | grep -o '"pendingRequest":{"id":"[^"]*"' | cut -d'"' -f6)
expect "another change while pending is refused" 409 "$(call PUT /api/vendor/prices/$RICE "$VTOKEN" '{"mrp":120.00,"sellingPrice":85.00}')"
sleep 2; r=$(call GET /api/catalog/products/$RICE)
echo "$r" | grep -q '"sellingPrice":90' && ok "live price unchanged while pending (₹90)" || bad "live price while pending" "$r"
poll "admin sees the request in the queue" 5 "$REQ_ID" GET "/api/admin/price-change-requests?status=PENDING&vendorId=$VENDOR_ID" "$ATOKEN"
expect "vendor cannot approve" 403 "$(call POST /api/admin/price-change-requests/$REQ_ID/approve "$VTOKEN" '{}')"
expect "admin approves" 200 "$(call POST /api/admin/price-change-requests/$REQ_ID/approve "$ATOKEN" '{"note":"ok"}')"
expect "second decision is refused" 409 "$(call POST /api/admin/price-change-requests/$REQ_ID/reject "$ATOKEN" '{"reason":"late"}')"
poll "approved price reaches search (₹50)" 10 '"sellingPrice":50' GET /api/catalog/products/$RICE
r=$(call PUT /api/vendor/prices/$RICE "$VTOKEN" '{"mrp":120.00,"sellingPrice":110.00}'); expect "+120% needs approval" 202 "$r"
REQ2=$(echo "$r" | body | grep -o '"pendingRequest":{"id":"[^"]*"' | cut -d'"' -f6)
expect "reject needs a reason" 400 "$(call POST /api/admin/price-change-requests/$REQ2/reject "$ATOKEN" '{}')"
expect "admin rejects with reason" 200 "$(call POST /api/admin/price-change-requests/$REQ2/reject "$ATOKEN" '{"reason":"Too high"}')"
expect "price history" 200 "$(call GET /api/vendor/prices/$RICE/history "$VTOKEN")"
expect "price DAL" 200 "$(call PUT /api/vendor/prices/$DAL "$VTOKEN" '{"mrp":80.00,"sellingPrice":72.00}')"

echo "== Inventory — stock rules"
r=$(adjust $RICE INCREASE 10 RESTOCK); expect "restock +10" 200 "$r"
poll "search shows in stock" 10 '"inStock":true' GET /api/catalog/products/$RICE
r=$(adjust $RICE DECREASE 15 DAMAGED); expect "cannot remove more than available" 409 "$r"
echo "$r" | grep -q '"errorCode":"INSUFFICIENT_STOCK"' && echo "$r" | grep -q '"available":10' && ok "INSUFFICIENT_STOCK reports available=10" || bad "insufficient stock body" "$r"
r=$(call PUT /api/vendor/inventory/$RICE/threshold "$VTOKEN" '{"lowStockThreshold":5}'); expect "threshold 5" 200 "$r"
r=$(call POST /api/vendor/inventory/$RICE/adjustments "$VTOKEN" '{"type":"SET","quantity":3,"reason":"STOCK_COUNT","note":"Shelf count"}'); expect "stock count → 3" 200 "$r"
echo "$r" | grep -q '"lowStock":true' && ok "low stock flagged" || bad "lowStock" "$r"
expect "sell out (−3)" 200 "$(adjust $RICE DECREASE 3 DAMAGED)"
poll "search shows out of stock" 10 '"inStock":false' GET /api/catalog/products/$RICE
r=$(call GET "/api/vendor/inventory?filter=OUT_OF_STOCK&q=Basmati" "$VTOKEN"); echo "$r" | grep -q "$RICE" && ok "out-of-stock filter" || bad "OUT_OF_STOCK filter" "$r"
expect "movement ledger" 200 "$(call GET /api/vendor/inventory/$RICE/movements "$VTOKEN")"
expect "summary" 200 "$(call GET /api/vendor/inventory/summary "$VTOKEN")"

echo "== Acceptance: concurrent stock updates never oversell or lose writes"
sleep 3   # let the per-user rate-limit bucket refill
echo "    25 parallel '+1' from 0:"; parallel 25 POST /api/vendor/inventory/$RICE/adjustments "$VTOKEN" '{"type":"INCREASE","quantity":1,"reason":"RESTOCK"}' | sed 's/^/      /'
r=$(call GET /api/vendor/inventory/$RICE "$VTOKEN"); [ "$(echo "$r" | num available)" = "25" ] && ok "no lost writes: available = 25" || bad "lost writes" "$r"
sleep 3
echo "    35 parallel '−1' with 25 in stock:"; out=$(parallel 35 POST /api/vendor/inventory/$RICE/adjustments "$VTOKEN" '{"type":"DECREASE","quantity":1,"reason":"DAMAGED"}'); echo "$out" | sed 's/^/      /'
echo "$out" | grep -q " 25 200" && echo "$out" | grep -q " 10 409" && ok "exactly 25 succeed, 10 refused" || bad "oversell check" "$out"
r=$(call GET /api/vendor/inventory/$RICE "$VTOKEN"); [ "$(echo "$r" | num available)" = "0" ] && ok "never below zero: available = 0" || bad "final stock" "$r"

echo "== Bulk update is all or nothing"
expect "stock DAL +20" 200 "$(adjust $DAL INCREASE 20 RESTOCK)"
r=$(call POST /api/vendor/inventory/adjustments/bulk "$VTOKEN" '{"items":[{"productId":"'"$DAL"'","type":"INCREASE","quantity":5,"reason":"RESTOCK"},{"productId":"'"$RICE"'","type":"DECREASE","quantity":999,"reason":"DAMAGED"}]}')
expect "bulk with one bad item fails" 409 "$r"
echo "$r" | grep -q "\"productId\":\"$RICE\"" && ok "error names the failing product" || bad "bulk error productId" "$r"
r=$(call GET /api/vendor/inventory/$DAL "$VTOKEN"); [ "$(echo "$r" | num available)" = "20" ] && ok "nothing applied (DAL still 20)" || bad "bulk atomicity" "$r"
expect "valid bulk applies" 200 "$(call POST /api/vendor/inventory/adjustments/bulk "$VTOKEN" '{"items":[{"productId":"'"$DAL"'","type":"INCREASE","quantity":5,"reason":"RESTOCK"},{"productId":"'"$RICE"'","type":"SET","quantity":7,"reason":"STOCK_COUNT"}]}')"

echo "== Acceptance: search reflects price and stock (filters and sorts)"
poll "in-stock filter" 10 "$RICE" GET "/api/search/products?vendorId=$VENDOR_ID&inStock=true"
poll "price range 40–60 finds rice (₹50) only" 5 "\"totalElements\":1.*$RICE|$RICE.*\"totalElements\":1" GET "/api/search/products?vendorId=$VENDOR_ID&minPrice=40&maxPrice=60"
r=$(call GET "/api/search/products?vendorId=$VENDOR_ID&sort=price_asc"); first=$(echo "$r" | body | json id)
[ "$first" = "$RICE" ] && ok "price_asc puts ₹50 rice before ₹72 dal" || bad "price sort" "$r"
expect "minPrice > maxPrice rejected" 400 "$(call GET "/api/search/products?minPrice=10&maxPrice=5")"

echo "== Rebuild: delete the products index, then republish catalog → prices → stock"
CONCRETE=$(curl -s "$ES/_alias/products" | sed -n 's/^{"\([^"]*\)".*/\1/p')
curl -s -o /dev/null -X DELETE "$ES/$CONCRETE" && ok "deleted $CONCRETE"
poll "product gone from search" 5 '^404 ' GET /api/catalog/products/$RICE
expect "republish catalog" 200 "$(call POST /api/admin/catalog/republish "$ATOKEN")"
expect "republish prices" 200 "$(call POST /api/admin/pricing/republish "$ATOKEN")"
expect "republish stock" 200 "$(call POST /api/admin/inventory/republish "$ATOKEN")"
poll "rebuilt with price and stock" 20 '"sellingPrice":50.*"inStock":true|"inStock":true.*"sellingPrice":50' GET /api/catalog/products/$RICE

echo "== Permissions"
CTOKEN=$(register "cust4-$RUN@example.com" CUSTOMER "Smoke Buyer")
expect "customer cannot see vendor inventory" 403 "$(call GET /api/vendor/inventory "$CTOKEN")"
expect "customer cannot set prices" 403 "$(call PUT /api/vendor/prices/$RICE "$CTOKEN" '{"mrp":1,"sellingPrice":1}')"
expect "vendor cannot read the approval queue" 403 "$(call GET /api/admin/price-change-requests "$VTOKEN")"
expect "the quote is internal (no gateway route)" 404 "$(call POST /internal/prices/quote "$CTOKEN" '{"productIds":["'"$RICE"'"]}')"

rm -rf "$VJAR" "$PAR"
echo
echo "Passed: $PASS   Failed: $FAIL"
[ "$FAIL" -eq 0 ]
