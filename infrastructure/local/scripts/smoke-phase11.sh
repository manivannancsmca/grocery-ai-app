#!/usr/bin/env bash
# =====================================================================================
# Phase 11 end-to-end smoke test — AI assistants with human approval.
# Uses the deterministic "scripted" model (no LLM needed); payments use the simulator.
# Prereq:
#   cd infrastructure/local && AI_PROVIDER=scripted AI_APPROVAL_TTL_CUSTOMER=30s AI_APPROVAL_EXPIRY_INTERVAL=5s \
#     docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders \
#     --profile realtime --profile assistant up -d --build
# Run (Git Bash):  bash infrastructure/local/scripts/smoke-phase11.sh
# At the end it recreates ai-orchestrator-service with AI_PROVIDER=ollama and no Ollama running (the "LLM down"
# check), then restores the scripted model. Exit code 0 = every check passed. Safe to re-run.
# =====================================================================================
set -uo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-admin@grocery.local}"
ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-Admin@12345}"
RUN="$(date +%s)"; TAG="q${RUN: -6}"
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
  local args=(-s -o "$out" -w '%{http_code}' -X "$method" "$GW$path" -H "X-Correlation-Id: smoke11-$RUN-$RANDOM")
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
admin_user() { # email role -> token
  call POST /api/admin/users "$ATOKEN" "{\"email\":\"$1\",\"fullName\":\"$2 $RUN\",\"password\":\"Admin1234\",\"roles\":[\"$2\"]}" >/dev/null
  login "$1" Admin1234
}
key() { echo "smoke11-$RUN-$1"; }
open_stream() { : > "$PAR/$1.sse"; curl -sN --max-time 900 -H "Authorization: Bearer $2" -H 'Accept: text/event-stream' "$GW/api/notifications/stream" > "$PAR/$1.sse" 2>/dev/null & STREAM_PID[$1]=$!; }
await() { local label=$1 name=$2 max=$3 pattern=$4 start; start=$(date +%s%N)
  for _ in $(seq 1 $((max * 10))); do grep -Eq -- "$pattern" "$PAR/$name.sse" && { ok "$label ($(( ($(date +%s%N) - start) / 1000000 )) ms)"; return 0; }; sleep 0.1; done
  bad "$label" "$(tail -c 400 "$PAR/$name.sse")"; return 1; }
sql() { docker exec grocery-mysql-1 mysql -uroot -p"${MYSQL_ROOT_PASSWORD:-root}" -N -e "$1" 2>/dev/null; }
# chat agent token message [conversationId] -> SSE transcript in $PAR/chat.sse, HTTP status in CHAT_STATUS
chat() {
  local payload; payload=$(python -c 'import json,sys; d={"message":sys.argv[1]}; sys.argv[2] and d.update(conversationId=sys.argv[2]); print(json.dumps(d))' "$3" "${4:-}")
  CHAT_STATUS=$(curl -sN --max-time 90 -o "$PAR/chat.sse" -w '%{http_code}' -X POST "$GW/api/ai/agents/$1/chat" \
    -H "Authorization: Bearer $2" -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    -H "X-Correlation-Id: smoke11-$RUN-$RANDOM" -d "$payload")
  CHAT="$(cat "$PAR/chat.sse")"
}
events() { grep -A1 "^event: *$1" "$PAR/chat.sse" | grep '^data:' | sed 's/^data: *//'; }   # data lines of one event type
tool_status() { events tool | grep "\"tool\":\"$1\"" | grep -o '"status":"[A-Z_]*"' | tail -1 | cut -d'"' -f4; }
approval_of_chat() { events approval | tail -1; }
ORCH=grocery-ai-orchestrator-service-1
envof() { docker inspect "$ORCH" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep "^$1=" | cut -d= -f2-; }
TTL=$(envof AI_APPROVAL_TTL_CUSTOMER); EXPIRY_EVERY=$(envof AI_APPROVAL_EXPIRY_INTERVAL)
restart_orchestrator() { # provider: recreate ai-orchestrator-service with that model provider and the same timings
  ( cd "$ROOT/infrastructure/local" && AI_PROVIDER=$1 AI_APPROVAL_TTL_CUSTOMER=${TTL:-15m} AI_APPROVAL_EXPIRY_INTERVAL=${EXPIRY_EVERY:-30s}       docker compose --profile assistant up -d --no-deps ai-orchestrator-service >/dev/null 2>&1 )
  for _ in $(seq 1 60); do [ "$(docker inspect -f '{{.State.Health.Status}}' "$ORCH" 2>/dev/null)" = healthy ] && break; sleep 3; done
}
cleanup() { for n in "${!STREAM_PID[@]}"; do kill "${STREAM_PID[$n]}" 2>/dev/null; done; rm -rf "$PAR"; }
trap cleanup EXIT

echo "== Preflight"
bash "$(dirname "$0")/check-connectors.sh" > "$PAR/conn" 2>&1 && ok "all connector tasks RUNNING (incl. ai-outbox)" || { bad "connector tasks" "$(cat "$PAR/conn")"; exit 1; }
grep -q "ai-outbox" "$PAR/conn" && ok "the ai-outbox connector is registered" || bad "ai-outbox connector" "$(cat "$PAR/conn")"
for s in gateway ai order payment cart notification; do expect "health/$s" 200 "$(call GET /api/health/$s)"; done
ATOKEN=$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD"); [ -n "$ATOKEN" ] && ok "admin login (SUPER_ADMIN)" || bad "admin login" "no token"
FTOKEN=$(admin_user "finance11-$RUN@example.com" FINANCE_ADMIN); [ -n "$FTOKEN" ] && ok "a FINANCE_ADMIN" || bad "finance admin" "no token"
OTOKEN=$(admin_user "ops11-$RUN@example.com" OPS_ADMIN); [ -n "$OTOKEN" ] && ok "an OPS_ADMIN" || bad "ops admin" "no token"
STOKEN=$(admin_user "support11-$RUN@example.com" SUPPORT); [ -n "$STOKEN" ] && ok "a SUPPORT agent" || bad "support" "no token"
r=$(call GET /api/ai/status "$ATOKEN"); expect "assistant status" 200 "$r"
has "… scripted model, available" "$(both '"provider":"scripted"' '"available":true')" "$r"

echo "== Setup: an approved vendor with a stocked product, a customer with an address"
CAT=$(call POST /api/admin/categories "$ATOKEN" "{\"name\":\"Smoke Assistant $RUN\"}" | body | json id)
VJAR="$(mktemp)"; VTOKEN=$(register "vendor11-$RUN@example.com" VENDOR "Assistant Mart" "$VJAR")
for _ in $(seq 1 60); do VID=$(call GET /api/vendors/me "$VTOKEN" | body | json id); [ -n "$VID" ] && break; sleep 0.25; done
call PUT /api/vendors/me "$VTOKEN" '{"businessName":"Assistant Mart '"$RUN"'","legalName":"Assistant Mart Pvt Ltd","gstin":"29ABCDE1234F1Z5","fssaiLicense":"12345678901234","contactEmail":"ops@assist.in","contactPhone":"+919876543210","address":{"line1":"4 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.97,"longitude":77.59}}' >/dev/null
call POST /api/vendors/me/submit "$VTOKEN" >/dev/null; call POST /api/admin/vendors/$VID/approve "$ATOKEN" >/dev/null
for _ in $(seq 1 20); do VTOKEN=$(refresh "$VJAR"); claims "$VTOKEN" | grep -q "\"vendor_id\":\"$VID\"" && break; sleep 0.5; done
RICE=$(call POST /api/vendor/products "$VTOKEN" '{"name":"Basmati '"$TAG"'","categoryId":"'"$CAT"'","unitValue":1,"unitOfMeasure":"KG","foodType":"VEG"}' | body | json id)
call POST /api/vendor/products/$RICE/publish "$VTOKEN" >/dev/null
for _ in $(seq 1 60); do [ "$(call GET /api/vendor/inventory/$RICE "$VTOKEN" | cut -d' ' -f1)" = 200 ] && [ "$(call GET /api/vendor/prices/$RICE "$VTOKEN" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
call PUT /api/vendor/prices/$RICE "$VTOKEN" '{"mrp":120.00,"sellingPrice":100.00}' >/dev/null
call POST /api/vendor/inventory/$RICE/adjustments "$VTOKEN" '{"type":"SET","quantity":40,"reason":"STOCK_COUNT"}' >/dev/null
poll "product \"Basmati $TAG\" is searchable and in stock" 20 '"inStock":true' GET "/api/search/products?q=basmati%20$TAG&inStock=true"
CJAR="$(mktemp)"; CTOKEN=$(register "cust11-$RUN@example.com" CUSTOMER "Asha Shopper" "$CJAR")
for _ in $(seq 1 60); do [ "$(call GET /api/customers/me "$CTOKEN" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
ADDR=$(call POST /api/customers/me/addresses "$CTOKEN" '{"label":"Home","recipientName":"Asha Shopper","phone":"+919876543210","line1":"1 Test Street","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.975,"longitude":77.60}' | body | json id)
[ -n "$ADDR" ] && ok "customer with a default address" || bad "customer address" "none"
open_stream customer "$CTOKEN"; open_stream admin "$ATOKEN"; open_stream finance "$FTOKEN"
orders_count() { call GET "/api/orders?size=100" "$CTOKEN" | body | grep -o '"orderNumber"' | wc -l; }

echo "== Agents follow the caller's role"
has "a customer gets the shopping assistant" '"id":"shopping"' "$(call GET /api/ai/agents "$CTOKEN")"
hasnt "… not the admin one" '"admin-bi"' "$(call GET /api/ai/agents "$CTOKEN")"
has "an admin gets the BI assistant" '"id":"admin-bi"' "$(call GET /api/ai/agents "$STOKEN")"
has "a vendor gets the vendor assistant (Phase 12)" '"id":"vendor"' "$(call GET /api/ai/agents "$VTOKEN")"

echo "== Acceptance: the agent searches and adds items to the cart"
chat shopping "$CTOKEN" "add 2 basmati $TAG"
[ "$CHAT_STATUS" = 200 ] && ok "chat answered as a stream (200)" || bad "chat status" "$CHAT_STATUS $CHAT"
CONV=$(events conversation | json conversationId); [ -n "$CONV" ] && ok "a conversation was started" || bad "conversation event" "$CHAT"
[ "$(tool_status searchProducts)" = SUCCEEDED ] && ok "tool searchProducts SUCCEEDED" || bad "searchProducts" "$(events tool)"
[ "$(tool_status addToCart)" = SUCCEEDED ] && ok "tool addToCart SUCCEEDED" || bad "addToCart" "$(events tool)"
grep -q '^event: *token' "$PAR/chat.sse" && grep -q '^event: *done' "$PAR/chat.sse" && ok "the answer streamed (token events) and finished (done)" || bad "token/done events" "$CHAT"
has "the cart really holds 2 × Basmati $TAG" "$(both "\"itemId\":\"$RICE\"" '"quantity":2')" "$(call GET /api/cart "$CTOKEN")"

echo "== Acceptance: \"place order\" produces an approval with the exact items and total"
PREVIEW_TOTAL=$(call GET "/api/cart/preview?addressId=$ADDR" "$CTOKEN" | body | grep -o '"total":[0-9.]*' | tail -1 | cut -d: -f2)
chat shopping "$CTOKEN" "checkout" "$CONV"
[ "$(tool_status previewCheckout)" = SUCCEEDED ] && ok "tool previewCheckout SUCCEEDED" || bad "previewCheckout" "$(events tool)"
[ "$(tool_status placeOrder)" = APPROVAL_REQUIRED ] && ok "tool placeOrder did NOT run: APPROVAL_REQUIRED" || bad "placeOrder" "$(events tool)"
A1=$(approval_of_chat); AP1=$(echo "$A1" | json id)
has "an approval card: PLACE_ORDER, pending" "$(both '"action":"PLACE_ORDER"' '"status":"PENDING_APPROVAL"')" "$A1"
has "… for the exact item and quantity" "Basmati $TAG[^\"]*× ?2|Basmati $TAG.{0,40}2" "$A1"
amt=$(echo "$A1" | num amount); [ -n "$amt" ] && [ "$(printf '%.2f' "$amt")" = "$(printf '%.2f' "$PREVIEW_TOTAL")" ] && ok "… and the checkout total ₹$PREVIEW_TOTAL" || bad "approval amount" "approval $amt vs preview $PREVIEW_TOTAL"
has "… which the customer may decide" '"canDecide":true' "$A1"
await "the customer is notified: approval needed" customer 15 "$(both '"type":"AI_APPROVAL_REQUESTED"' "$AP1")"
has "it is in the customer's to-decide list" "$AP1" "$(call GET "/api/approvals?scope=to-decide" "$CTOKEN")"
expect "an admin cannot even see a customer's purchase approval" 404 "$(call POST /api/approvals/$AP1/approve "$ATOKEN" '{}')"

echo "== Acceptance: approving executes once"
before=$(orders_count)
r=$(call POST /api/approvals/$AP1/approve "$CTOKEN" '{}'); expect "the customer approves" 200 "$r"
has "… executed: COMPLETED with an order number" "$(both '"status":"COMPLETED"' '"orderNumber":"GA-')" "$r"
ON1=$(echo "$r" | body | json orderNumber); O1=$(echo "$r" | body | json orderId)
r2=$(call POST /api/approvals/$AP1/approve "$CTOKEN" '{}'); expect "approving again → 409" 409 "$r2"
has "… APPROVAL_NOT_PENDING, currentStatus COMPLETED" "$(both '"errorCode":"APPROVAL_NOT_PENDING"' '"currentStatus":"COMPLETED"')" "$r2"
sleep 1; after=$(orders_count)
[ $((after - before)) = 1 ] && ok "exactly one order was created ($ON1)" || bad "one order" "$before → $after"
has "the order holds the approved items and total" "$(both "Basmati $TAG" "\"total\":$PREVIEW_TOTAL")" "$(call GET /api/orders/$O1 "$CTOKEN")"
await "the customer is notified: done" customer 15 "$(both '"type":"AI_ACTION_COMPLETED"' "$AP1")"

echo "== Acceptance: reject or expiry means nothing runs"
chat shopping "$CTOKEN" "add 1 basmati $TAG" "$CONV" >/dev/null
chat shopping "$CTOKEN" "checkout" "$CONV"; AP2=$(approval_of_chat | json id)
[ -n "$AP2" ] && ok "a second order proposal" || bad "second proposal" "$CHAT"
expect "rejecting needs a comment" 400 "$(call POST /api/approvals/$AP2/reject "$CTOKEN" '{}')"
has "the customer rejects it" '"status":"REJECTED"' "$(call POST /api/approvals/$AP2/reject "$CTOKEN" '{"comment":"Not now"}')"
expect "a rejected approval cannot be approved" 409 "$(call POST /api/approvals/$AP2/approve "$CTOKEN" '{}')"
chat shopping "$CTOKEN" "checkout" "$CONV"; AP3=$(approval_of_chat | json id)
[ -n "$AP3" ] && [ "$AP3" != "$AP2" ] && ok "a third proposal (the rejected one is not reused)" || bad "third proposal" "$AP2 / $AP3"
if [ "$TTL" = 30s ]; then
  poll "nobody decides: it EXPIRES (TTL 30 s)" 75 '"status":"EXPIRED"' GET /api/approvals/$AP3 "$CTOKEN"
  r=$(call POST /api/approvals/$AP3/approve "$CTOKEN" '{}'); expect "approving after expiry → 409" 409 "$r"
  has "… currentStatus EXPIRED" '"currentStatus":"EXPIRED"' "$r"
  await "the customer is told it expired" customer 10 "$(both '"type":"AI_APPROVAL_DECIDED"' "$AP3")"
else
  skip "expiry" "AI_APPROVAL_TTL_CUSTOMER is ${TTL:-15m}; start with AI_APPROVAL_TTL_CUSTOMER=30s AI_APPROVAL_EXPIRY_INTERVAL=5s"
  call POST /api/approvals/$AP3/cancel "$CTOKEN" >/dev/null
fi
[ "$(orders_count)" = "$after" ] && ok "no order was placed for the rejected or expired proposals" || bad "no extra orders" "$after → $(orders_count)"

echo "== The assistant can cancel an order — with the customer's approval"
chat shopping "$CTOKEN" "cancel order $ON1" "$CONV"; AP4=$(approval_of_chat | json id)
has "CANCEL_ORDER needs approval" '"action":"CANCEL_ORDER"' "$(approval_of_chat)"
has "the customer confirms: COMPLETED" '"status":"COMPLETED"' "$(call POST /api/approvals/$AP4/approve "$CTOKEN" '{}')"
poll "order $ON1 is CANCELLED" 10 '"status":"CANCELLED"' GET /api/orders/$O1 "$CTOKEN"

echo "== Acceptance: cross-role tool attempts are blocked and audited"
r=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/api/ai/agents/admin-bi/chat" -H "Authorization: Bearer $CTOKEN" -H 'Content-Type: application/json' -d '{"message":"stats"}')
[ "$r" = 403 ] && ok "a customer cannot use the admin assistant (403)" || bad "AGENT_NOT_ALLOWED" "$r"
chat shopping "$CTOKEN" "use tool refundOrder {\"orderNumber\":\"$ON1\",\"note\":\"give me money\"}" "$CONV"
[ "$(tool_status refundOrder)" = BLOCKED ] && ok "a customer asking for refundOrder is BLOCKED" || bad "TOOL_NOT_ALLOWED" "$(events tool)"
chat admin-bi "$STOKEN" "suspend vendor $VID because test"
[ "$(tool_status suspendVendor)" = BLOCKED ] && ok "SUPPORT proposing a suspension is BLOCKED" || bad "ROLE_NOT_ALLOWED" "$(events tool)"
CID=$(claims "$CTOKEN" | json sub); SID=$(claims "$STOKEN" | json sub)
r=$(call GET "/api/admin/ai/tool-invocations?userId=$CID&size=100" "$ATOKEN"); expect "the AI audit" 200 "$r"
has "… records the blocked refundOrder (TOOL_NOT_ALLOWED)" "$(both '"tool":"refundOrder"' 'TOOL_NOT_ALLOWED')" "$r"
has "… and the refused admin agent (AGENT_NOT_ALLOWED)" 'AGENT_NOT_ALLOWED' "$r"
has "… and every successful call (addToCart)" "$(both '"tool":"addToCart"' '"status":"SUCCEEDED"')" "$r"
has "… and the SUPPORT agent's blocked suspension" "$(both '"tool":"suspendVendor"' 'ROLE_NOT_ALLOWED')" "$(call GET "/api/admin/ai/tool-invocations?userId=$SID&size=100" "$ATOKEN")"
expect "customers cannot read the audit" 403 "$(call GET /api/admin/ai/tool-invocations "$CTOKEN")"

echo "== Admin assistant: read tools and structured insights"
chat admin-bi "$ATOKEN" "stats"
[ "$(tool_status getOrderStats)" = SUCCEEDED ] && ok "tool getOrderStats SUCCEEDED" || bad "getOrderStats" "$(events tool)"
chat admin-bi "$ATOKEN" "stuck orders"
[ "$(tool_status listStuckOrders)" = SUCCEEDED ] && ok "tool listStuckOrders SUCCEEDED" || bad "listStuckOrders" "$(events tool)"
r=$(call POST /api/ai/agents/admin-bi/insights "$OTOKEN" '{}'); expect "insights report (structured output)" 200 "$r"
has "… with headline, KPIs, observations, risks and suggested actions" "\"headline\":\".+\".*\"kpis\":\[.*\"observations\":\[.*\"risks\":\[.*\"suggestedActions\":\[" "$r"
has "… generated by the model" '"provider":"scripted"' "$r"

echo "== Acceptance: tampered parameters are refused by the target service"
call DELETE /api/cart "$CTOKEN" >/dev/null
call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$RICE"'","quantity":2}' >/dev/null
T=$(call GET "/api/cart/preview?addressId=$ADDR" "$CTOKEN" | body | grep -o '"total":[0-9.]*' | tail -1 | cut -d: -f2)
O5=$(call POST /api/orders "$CTOKEN" "{\"addressId\":\"$ADDR\",\"expectedTotal\":$T}" "Idempotency-Key: $(key paid)" | body | json id)
for _ in $(seq 1 60); do [ "$(call GET /api/payments/orders/$O5/checkout "$CTOKEN" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
b=$(call POST /api/payments/simulator/orders/$O5/pay "$CTOKEN" '{"outcome":"SUCCESS"}' | body)
call POST /api/payments/razorpay/verify "$CTOKEN" "{\"razorpayOrderId\":\"$(echo "$b" | json razorpayOrderId)\",\"razorpayPaymentId\":\"$(echo "$b" | json razorpayPaymentId)\",\"razorpaySignature\":\"$(echo "$b" | json razorpaySignature)\"}" >/dev/null
poll "a paid order to refund" 20 '"status":"CONFIRMED"' GET /api/orders/$O5 "$CTOKEN"
ON5=$(echo "$LAST" | body | json orderNumber)
chat admin-bi "$OTOKEN" "refund 10 for $ON5 because Damaged packet"
A6=$(approval_of_chat); AP6=$(echo "$A6" | json id)
has "an OPS_ADMIN may propose a refund; it needs FINANCE_ADMIN or SUPER_ADMIN" "$(both '"action":"REFUND_PAYMENT"' 'FINANCE_ADMIN')" "$A6"
await "finance admins are notified" finance 15 "$(both '"type":"AI_APPROVAL_REQUESTED"' "$AP6")"
expect "the proposing OPS_ADMIN cannot approve it (not a finance role)" 403 "$(call POST /api/approvals/$AP6/approve "$OTOKEN" '{}')"
# Someone with database access changes the stored amount after the proposal (10.00 → 99.00).
n=$(sql "UPDATE ai_db.approval_request SET request_body = REGEXP_REPLACE(request_body, '\"amount\":[0-9.]+', '\"amount\":99.00') WHERE id = UUID_TO_BIN('$AP6'); SELECT ROW_COUNT();")
[ "$n" = 1 ] && ok "the stored refund body was tampered with (10.00 → 99.00)" || bad "tamper" "rows: $n"
r=$(call POST /api/approvals/$AP6/approve "$FTOKEN" '{"comment":"ok"}'); expect "the finance admin approves" 200 "$r"
has "… payment-service refuses it: FAILED, APPROVAL_BODY_MISMATCH" "$(both '"status":"FAILED"' 'APPROVAL_BODY_MISMATCH')" "$r"
hasnt "… and no refund was made" '"refunds":\[\{' "$(call GET "/api/admin/payments?orderId=$O5" "$ATOKEN")"
chat admin-bi "$OTOKEN" "refund 10 for $ON5 because Damaged packet"; AP7=$(approval_of_chat | json id)
r=$(call POST /api/approvals/$AP7/approve "$FTOKEN" '{"comment":"ok"}')
has "an untampered refund proposal is approved and COMPLETED" '"status":"COMPLETED"' "$r"
poll "… ₹10.00 refunded once" 20 '"amount":10\.0' GET "/api/admin/payments?orderId=$O5" "$ATOKEN"

echo "== Four-eyes: a suspension needs a second admin"
V2JAR="$(mktemp)"; V2TOKEN=$(register "vendor11b-$RUN@example.com" VENDOR "Shady Mart" "$V2JAR")
for _ in $(seq 1 60); do V2=$(call GET /api/vendors/me "$V2TOKEN" | body | json id); [ -n "$V2" ] && break; sleep 0.25; done
call PUT /api/vendors/me "$V2TOKEN" '{"businessName":"Shady Mart '"$RUN"'","legalName":"Shady Mart Pvt Ltd","gstin":"29ABCDE1234F1Z5","fssaiLicense":"12345678901234","contactEmail":"ops@shady.in","contactPhone":"+919876543210","address":{"line1":"9 Far Road","city":"Mysuru","state":"Karnataka","postalCode":"570001","country":"IN","latitude":12.30,"longitude":76.64}}' >/dev/null
call POST /api/vendors/me/submit "$V2TOKEN" >/dev/null; call POST /api/admin/vendors/$V2/approve "$ATOKEN" >/dev/null
chat admin-bi "$OTOKEN" "suspend vendor $V2 because Fraud reports"
A8=$(approval_of_chat); AP8=$(echo "$A8" | json id)
has "SUSPEND_VENDOR proposed, four-eyes" "$(both '"action":"SUSPEND_VENDOR"' '"fourEyes":true')" "$A8"
r=$(call POST /api/approvals/$AP8/approve "$OTOKEN" '{}'); expect "the proposer cannot approve it" 403 "$r"
has "… FOUR_EYES_REQUIRED" 'FOUR_EYES_REQUIRED' "$r"
has "a second admin approves: COMPLETED" '"status":"COMPLETED"' "$(call POST /api/approvals/$AP8/approve "$ATOKEN" '{"comment":"Confirmed"}')"
poll "the vendor is SUSPENDED" 10 '"status":"SUSPENDED"' GET /api/admin/vendors/$V2 "$ATOKEN"

echo "== Monitoring"
r=$(call GET /api/admin/ai/summary "$ATOKEN"); expect "AI summary" 200 "$r"
has "… counts tool calls, blocked attempts and approvals" "\"toolCalls\":[1-9].*\"blocked\":[1-9].*\"completed\":[1-9]" "$r"
expect "the approval signing keys are not exposed through the gateway" 404 "$(call GET /.well-known/approval-jwks.json "$ATOKEN")"

echo "== Acceptance: with the LLM down, the chat degrades gracefully"
restart_orchestrator ollama
ok "ai-orchestrator-service restarted with AI_PROVIDER=ollama and no Ollama running"
poll "status: available false" 40 '"available":false' GET /api/ai/status "$CTOKEN"
chat shopping "$CTOKEN" "search basmati"
[ "$CHAT_STATUS" = 200 ] && ok "the chat still answers (200)" || bad "chat while down" "$CHAT_STATUS"
grep -q '^event: *error' "$PAR/chat.sse" && events error | grep -q 'AI_UNAVAILABLE' && ok "… with a friendly error event AI_UNAVAILABLE" || bad "AI_UNAVAILABLE event" "$CHAT"
expect "shopping keeps working: cart" 200 "$(call GET /api/cart "$CTOKEN")"
expect "… and orders" 200 "$(call GET /api/orders "$CTOKEN")"
r=$(call POST /api/ai/agents/admin-bi/insights "$ATOKEN" '{}'); expect "insights still answer" 200 "$r"
has "… from the rule-based fallback" '"provider":"rules"' "$r"
restart_orchestrator scripted
poll "scripted model restored: available again" 40 '"available":true' GET /api/ai/status "$CTOKEN"

rm -f "$VJAR" "$CJAR" "$V2JAR"
echo
echo "Passed: $PASS   Failed: $FAIL   Skipped: $SKIP"
[ "$FAIL" -eq 0 ]
