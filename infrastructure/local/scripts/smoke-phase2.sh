#!/usr/bin/env bash
# =====================================================================================
# Phase 2 end-to-end smoke test — everything through the API gateway (http://localhost:8080).
# Prereq: cd infrastructure/local && docker compose --profile core up -d --build
# Run (Git Bash):  bash infrastructure/local/scripts/smoke-phase2.sh
# Exit code 0 = every check passed.
# =====================================================================================
set -uo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-admin@grocery.local}"
ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-Admin@12345}"
RUN="$(date +%s)"
JAR="$(mktemp)"            # cookie jar for the vendor's refresh-token cookie
PASS=0; FAIL=0

ok()   { echo "  ✔ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ✘ $1"; echo "    → $2" | head -c 600; echo; FAIL=$((FAIL+1)); }
json() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -1; }   # tiny JSON field extractor
claims() { local p; p=$(echo "$1" | cut -d. -f2 | tr '_-' '/+'); while [ $(( ${#p} % 4 )) -ne 0 ]; do p="$p="; done; echo "$p" | base64 -d 2>/dev/null; }
call() {  # method path token [body] -> prints "STATUS BODY"
  local method=$1 path=$2 token=${3:-} body=${4:-}
  local args=(-s -o /tmp/smoke_body -w '%{http_code}' -X "$method" "$GW$path" -H "X-Correlation-Id: smoke-$RUN-$RANDOM")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local status; status=$(curl "${args[@]}"); echo "$status $(cat /tmp/smoke_body)"
}
expect() { # label expectedStatus "STATUS BODY"
  local status=${3%% *}; if [ "$status" = "$2" ]; then ok "$1 ($status)"; else bad "$1 (expected $2, got $status)" "${3#* }"; fi
}
wait_for() { # label path token expectedStatus — retries up to ~15s (event-driven steps)
  for _ in $(seq 1 15); do r=$(call GET "$2" "$3"); [ "${r%% *}" = "$4" ] && { ok "$1 (${r%% *})"; echo "$r" > /tmp/smoke_last; return; }; sleep 1; done
  bad "$1" "$r"; echo "$r" > /tmp/smoke_last
}

echo "== Health (public)"
for s in gateway identity customer vendor restaurant delivery; do expect "health/$s" 200 "$(call GET /api/health/$s)"; done

echo "== Registration & login"
VEMAIL="vendor-$RUN@example.com"
r=$(curl -s -c "$JAR" -o /tmp/smoke_body -w '%{http_code}' -X POST "$GW/api/auth/register" -H 'Content-Type: application/json' \
     -d "{\"email\":\"$VEMAIL\",\"password\":\"Secret123\",\"fullName\":\"Smoke Vendor\",\"phone\":\"+919876543210\",\"role\":\"VENDOR\"}")
expect "register vendor" 201 "$r $(cat /tmp/smoke_body)"
VTOKEN=$(json accessToken < /tmp/smoke_body)
grep -q grocery_rt "$JAR" && ok "refresh cookie set (httpOnly)" || bad "refresh cookie" "$(cat "$JAR")"
expect "duplicate email rejected" 409 "$(call POST /api/auth/register "" "{\"email\":\"$VEMAIL\",\"password\":\"Secret123\",\"fullName\":\"X\",\"role\":\"VENDOR\"}")"
expect "wrong password" 401 "$(call POST /api/auth/login "" "{\"email\":\"$VEMAIL\",\"password\":\"Wrong1234\"}")"
expect "no token -> 401" 401 "$(call GET /api/vendors/me)"

echo "== Vendor onboarding (draft created from the UserRegistered event via Debezium + Kafka)"
wait_for "vendor draft exists" /api/vendors/me "$VTOKEN" 200
VENDOR_ID=$(json id < <(cut -d' ' -f2- /tmp/smoke_last))
expect "incomplete submit -> 422" 422 "$(call POST /api/vendors/me/submit "$VTOKEN")"
PROFILE='{"businessName":"Smoke Mart","legalName":"Smoke Mart Pvt Ltd","gstin":"29ABCDE1234F1Z5","fssaiLicense":"12345678901234","contactEmail":"ops@smoke.in","contactPhone":"+919876543210","address":{"line1":"1 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.97,"longitude":77.59}}'
expect "save profile" 200 "$(call PUT /api/vendors/me "$VTOKEN" "$PROFILE")"
expect "submit for approval" 200 "$(call POST /api/vendors/me/submit "$VTOKEN")"
expect "customer role cannot use vendor API" 403 "$(call GET /api/vendors/me "$(curl -s -X POST "$GW/api/auth/register" -H 'Content-Type: application/json' -d "{\"email\":\"c-$RUN@example.com\",\"password\":\"Secret123\",\"fullName\":\"C\",\"role\":\"CUSTOMER\"}" | json accessToken)")"

echo "== Admin approval → vendor_id claim"
ATOKEN=$(call POST /api/auth/login "" "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | cut -d' ' -f2- | json accessToken)
[ -n "$ATOKEN" ] && ok "bootstrap admin login" || bad "bootstrap admin login" "no token"
expect "vendor sees no admin API" 403 "$(call GET /api/admin/vendors "$VTOKEN")"
expect "pending queue" 200 "$(call GET "/api/admin/vendors?status=PENDING_APPROVAL&query=smoke" "$ATOKEN")"
expect "approve vendor" 200 "$(call POST /api/admin/vendors/$VENDOR_ID/approve "$ATOKEN")"

# identity consumes VendorApproved and revokes old tokens; the old token must start failing with TOKEN_REVOKED
for _ in $(seq 1 15); do r=$(call GET /api/vendors/me "$VTOKEN"); echo "$r" | grep -q TOKEN_REVOKED && break; sleep 1; done
echo "$r" | grep -q TOKEN_REVOKED && ok "old token revoked after approval (401 TOKEN_REVOKED)" || bad "old token revoked" "$r"
r=$(curl -s -b "$JAR" -c "$JAR" -o /tmp/smoke_body -w '%{http_code}' -X POST "$GW/api/auth/refresh" -H 'X-Requested-With: XMLHttpRequest')
expect "refresh with cookie" 200 "$r $(cat /tmp/smoke_body)"
NEW_TOKEN=$(json accessToken < /tmp/smoke_body)
claims "$NEW_TOKEN" | grep -q "\"vendor_id\":\"$VENDOR_ID\"" && ok "new token carries vendor_id" || bad "vendor_id claim" "$(claims "$NEW_TOKEN")"
expect "approved vendor profile" 200 "$(call GET /api/vendors/me "$NEW_TOKEN")"

echo "== Suspension (gateway blocks old tokens at once)"
expect "suspend vendor" 200 "$(call POST /api/admin/vendors/$VENDOR_ID/suspend "$ATOKEN" '{"reason":"smoke test"}')"
for _ in $(seq 1 15); do r=$(call GET /api/vendors/me "$NEW_TOKEN"); echo "$r" | grep -q TOKEN_REVOKED && break; sleep 1; done
echo "$r" | grep -q TOKEN_REVOKED && ok "suspended vendor's token revoked" || bad "suspension revocation" "$r"
expect "reinstate vendor" 200 "$(call POST /api/admin/vendors/$VENDOR_ID/reinstate "$ATOKEN")"
expect "status history" 200 "$(call GET /api/admin/vendors/$VENDOR_ID/history "$ATOKEN")"

echo "== Customer, restaurant and delivery partner onboarding"
CTOKEN=$(curl -s -X POST "$GW/api/auth/register" -H 'Content-Type: application/json' -d "{\"email\":\"cust-$RUN@example.com\",\"password\":\"Secret123\",\"fullName\":\"Smoke Customer\",\"role\":\"CUSTOMER\"}" | json accessToken)
wait_for "customer profile created" /api/customers/me "$CTOKEN" 200
expect "add address" 201 "$(call POST /api/customers/me/addresses "$CTOKEN" '{"label":"Home","recipientName":"Smoke Customer","phone":"+919876543210","line1":"12 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.97,"longitude":77.59}')"
RTOKEN=$(curl -s -X POST "$GW/api/auth/register" -H 'Content-Type: application/json' -d "{\"email\":\"rest-$RUN@example.com\",\"password\":\"Secret123\",\"fullName\":\"Smoke Kitchen\",\"role\":\"RESTAURANT_OWNER\"}" | json accessToken)
wait_for "restaurant draft created" /api/restaurants/me "$RTOKEN" 200
DTOKEN=$(curl -s -X POST "$GW/api/auth/register" -H 'Content-Type: application/json' -d "{\"email\":\"rider-$RUN@example.com\",\"password\":\"Secret123\",\"fullName\":\"Smoke Rider\",\"role\":\"DELIVERY_PARTNER\"}" | json accessToken)
wait_for "delivery partner draft created" /api/delivery/partners/me "$DTOKEN" 200

echo "== Restaurant approval → restaurant_id claim"
RJAR="$(mktemp)"
curl -s -c "$RJAR" -o /dev/null -X POST "$GW/api/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"rest-$RUN@example.com\",\"password\":\"Secret123\"}"
RPROFILE='{"name":"Smoke Kitchen","description":"Test kitchen","cuisines":["NORTH_INDIAN"],"fssaiLicense":"12345678901234","contactEmail":"k@smoke.in","contactPhone":"+919876543210","address":{"line1":"2 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.97,"longitude":77.6},"openingHours":[{"dayOfWeek":"MONDAY","opensAt":"09:00","closesAt":"23:00"}]}'
expect "save restaurant profile" 200 "$(call PUT /api/restaurants/me "$RTOKEN" "$RPROFILE")"
REST_ID=$(cut -d' ' -f2- <<< "$(call GET /api/restaurants/me "$RTOKEN")" | json id)
expect "submit restaurant" 200 "$(call POST /api/restaurants/me/submit "$RTOKEN")"
expect "approve restaurant" 200 "$(call POST /api/admin/restaurants/$REST_ID/approve "$ATOKEN")"
for _ in $(seq 1 15); do
  r=$(curl -s -b "$RJAR" -c "$RJAR" -X POST "$GW/api/auth/refresh" -H 'X-Requested-With: XMLHttpRequest')
  claims "$(echo "$r" | json accessToken)" | grep -q "\"restaurant_id\":\"$REST_ID\"" && break; sleep 1
done
claims "$(echo "$r" | json accessToken)" | grep -q "\"restaurant_id\":\"$REST_ID\"" && ok "token carries restaurant_id" || bad "restaurant_id claim" "$r"
rm -f "$RJAR"

echo "== Delivery manager approves a partner"
MEMAIL="dm-$RUN@example.com"
expect "super admin creates DELIVERY_MANAGER" 201 "$(call POST /api/admin/users "$ATOKEN" "{\"email\":\"$MEMAIL\",\"fullName\":\"Fleet Lead\",\"password\":\"Fleet1234\",\"roles\":[\"DELIVERY_MANAGER\"]}")"
MTOKEN=$(call POST /api/auth/login "" "{\"email\":\"$MEMAIL\",\"password\":\"Fleet1234\"}" | cut -d' ' -f2- | json accessToken)
expect "save partner profile" 200 "$(call PUT /api/delivery/partners/me "$DTOKEN" '{"fullName":"Smoke Rider","phone":"+919876543210","city":"Bengaluru","vehicleType":"BICYCLE"}')"
expect "submit partner" 200 "$(call POST /api/delivery/partners/me/submit "$DTOKEN")"
PARTNER_ID=$(claims "$DTOKEN" | sed -n 's/.*"sub":"\([^"]*\)".*/\1/p')
expect "delivery manager approves partner" 200 "$(call POST /api/admin/delivery-partners/$PARTNER_ID/approve "$MTOKEN")"
expect "delivery manager cannot approve vendors" 403 "$(call POST /api/admin/vendors/$VENDOR_ID/suspend "$MTOKEN" '{"reason":"x"}')"

echo "== Refresh-token reuse detection"
OLD=$(grep grocery_rt "$JAR" | awk '{print $7}')
curl -s -b "$JAR" -c "$JAR" -o /dev/null -X POST "$GW/api/auth/refresh" -H 'X-Requested-With: XMLHttpRequest'
sleep 11   # past the 10 s concurrent-refresh grace window
r=$(curl -s -o /tmp/smoke_body -w '%{http_code}' -X POST "$GW/api/auth/refresh" -H 'X-Requested-With: XMLHttpRequest' -H "Cookie: grocery_rt=$OLD")
expect "reused old refresh token rejected" 401 "$r $(cat /tmp/smoke_body)"
r=$(curl -s -b "$JAR" -o /tmp/smoke_body -w '%{http_code}' -X POST "$GW/api/auth/refresh" -H 'X-Requested-With: XMLHttpRequest')
expect "whole session family revoked" 401 "$r $(cat /tmp/smoke_body)"

rm -f "$JAR" /tmp/smoke_body /tmp/smoke_last
echo
echo "Passed: $PASS   Failed: $FAIL"
[ "$FAIL" -eq 0 ]
