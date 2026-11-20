#!/usr/bin/env bash
# =====================================================================================
# Phase 10 end-to-end smoke test — event-driven hardening.
# Prereq (short timings so every check fits in a few minutes; payments use the simulator):
#   cd infrastructure/local && PAYMENT_RECONCILE_INTERVAL=5s PAYMENT_RECONCILE_AFTER=10s ORDER_WATCHDOG_INTERVAL=5s \
#     ORDER_STUCK_CONFIRMED_AFTER=20s OUTBOX_RETENTION=2m OUTBOX_CLEANUP_INTERVAL=30s HOUSEKEEPING_INITIAL_DELAY=30s CONNECT_WATCHDOG_INTERVAL=10 \
#     docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders --profile realtime up -d --build
# Run (Git Bash):  bash infrastructure/local/scripts/smoke-phase10.sh
# It briefly breaks and restores the identity connector (connector-health check).
# Exit code 0 = every check passed. Safe to re-run: every run uses its own users, store and names.
# =====================================================================================
set -uo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
CONNECT="${CONNECT_URL:-http://localhost:8083}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
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
  local args=(-s -o "$out" -w '%{http_code}' -X "$method" "$GW$path" -H "X-Correlation-Id: smoke10-$RUN-$RANDOM")
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
key() { echo "smoke10-$RUN-$1"; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || python -c "import uuid;print(uuid.uuid4())"; }
micros() { echo $(( $(date +%s%N) / 1000 )); }
open_stream() { : > "$PAR/$1.sse"; curl -sN --max-time 900 -H "Authorization: Bearer $2" -H 'Accept: text/event-stream' "$GW/api/notifications/stream" > "$PAR/$1.sse" 2>/dev/null & STREAM_PID[$1]=$!; }
await() { local label=$1 name=$2 max=$3 pattern=$4 start; start=$(date +%s%N)
  for _ in $(seq 1 $((max * 10))); do grep -Eq -- "$pattern" "$PAR/$name.sse" && { ok "$label ($(( ($(date +%s%N) - start) / 1000000 )) ms)"; return 0; }; sleep 0.1; done
  bad "$label" "$(tail -c 400 "$PAR/$name.sse")"; return 1; }
produce() { # topic, then one line "<headers>\t<key>|<value>" on stdin (headers: k:v,k:v)
  MSYS_NO_PATHCONV=1 docker exec -i grocery-kafka-1 /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic "$1" \
    --property parse.key=true --property 'key.separator=|' --property parse.headers=true >/dev/null 2>&1
}
sql() { docker exec grocery-mysql-1 mysql -uroot -p"${MYSQL_ROOT_PASSWORD:-root}" -N -e "$1" 2>/dev/null; }
cleanup() { for n in "${!STREAM_PID[@]}"; do kill "${STREAM_PID[$n]}" 2>/dev/null; done; rm -rf "$PAR"; }
trap cleanup EXIT

echo "== Preflight"
bash "$(dirname "$0")/check-connectors.sh" > "$PAR/conn" 2>&1 && ok "all connector tasks RUNNING" || { bad "connector tasks" "$(cat "$PAR/conn")"; exit 1; }
for s in gateway order payment notification inventory vendor identity catalog-command; do expect "health/$s" 200 "$(call GET /api/health/$s)"; done
ATOKEN=$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD"); [ -n "$ATOKEN" ] && ok "admin login" || bad "admin login" "no token"
SEMAIL="support10-$RUN@example.com"
expect "super admin creates a SUPPORT agent" 201 "$(call POST /api/admin/users "$ATOKEN" "{\"email\":\"$SEMAIL\",\"fullName\":\"Ops Support\",\"password\":\"Support1234\",\"roles\":[\"SUPPORT\"]}")"
STOKEN=$(login "$SEMAIL" Support1234); [ -n "$STOKEN" ] && ok "support login" || bad "support login" "no token"
open_stream admin "$ATOKEN"

echo "== Acceptance: a poison message goes to the DLT without blocking its partition"
VJAR="$(mktemp)"; VTOKEN=$(register "vendor10-$RUN@example.com" VENDOR "Hardening Mart" "$VJAR")
for _ in $(seq 1 60); do VID=$(call GET /api/vendors/me "$VTOKEN" | body | json id); [ -n "$VID" ] && break; sleep 0.25; done
POISON=$(uuid)
printf 'eventId:%s,eventType:VendorSubmitted,eventVersion:1,aggregateType:Vendor,occurredAt:%s\t%s|{"broken":\n' "$POISON" "$(micros)" "$VID" | produce vendor.events
ok "poison VendorSubmitted ($POISON) produced on vendor.events with key = the vendor id"
call PUT /api/vendors/me "$VTOKEN" '{"businessName":"Hardening Mart '"$RUN"'","legalName":"Hardening Mart Pvt Ltd","gstin":"29ABCDE1234F1Z5","fssaiLicense":"12345678901234","contactEmail":"ops@harden.in","contactPhone":"+919876543210","address":{"line1":"4 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.97,"longitude":77.59}}' >/dev/null
call POST /api/vendors/me/submit "$VTOKEN" >/dev/null
await "the real VendorSubmitted behind it (same key, same partition) is still processed" admin 15 "$(both '"type":"APPROVAL_REQUESTED"' "Hardening Mart $RUN")"
for svc in notification identity catalog-command; do
  poll "$svc: the poison is listed as a dead letter (InvalidEventException)" 20 "$(both "$POISON" 'InvalidEventException')" GET "/api/admin/ops/$svc/dead-letters?eventType=VendorSubmitted&size=100" "$ATOKEN"
done
DL=$(echo "$LAST" | body | grep -o "\"id\":\"[^\"]*\"[^}]*$POISON" | head -1 | cut -d'"' -f4)
DLN=$(call GET "/api/admin/ops/notification/dead-letters?eventType=VendorSubmitted&size=100" "$ATOKEN" | body | grep -o "\"id\":\"vendor.events.DLT:[0-9]*:[0-9]*\"[^}]*$POISON" | head -1 | cut -d'"' -f4)
[ -n "$DLN" ] && ok "notification's dead letter id $DLN" || bad "dead letter id" "not found"
expect "a support agent may look" 200 "$(call GET "/api/admin/ops/notification/dead-letters/$DLN" "$STOKEN")"
expect "… but not replay" 403 "$(call POST "/api/admin/ops/notification/dead-letters/$DLN/replay" "$STOKEN")"
expect "a vendor cannot see dead letters" 403 "$(call GET /api/admin/ops/notification/dead-letters "$VTOKEN")"
r=$(call POST "/api/admin/ops/notification/dead-letters/$DLN/replay" "$ATOKEN"); expect "replaying a poison message" 200 "$r"
has "… fails again (REPLAY_FAILED) and stays in the DLT" '"status":"REPLAY_FAILED"' "$r"
expect "discard requires a reason" 400 "$(call POST "/api/admin/ops/notification/dead-letters/$DLN/discard" "$ATOKEN" '{}')"
has "discard it" '"status":"DISCARDED"' "$(call POST "/api/admin/ops/notification/dead-letters/$DLN/discard" "$ATOKEN" '{"reason":"Malformed test message"}')"
expect "a discarded dead letter cannot be replayed" 409 "$(call POST "/api/admin/ops/notification/dead-letters/$DLN/replay" "$ATOKEN")"

echo "== Acceptance: replay is idempotent"
GOOD=$(uuid); OWNER=$(claims "$VTOKEN" | json sub)
PAYLOAD='{"vendorId":"'"$VID"'","ownerUserId":"'"$OWNER"'","businessName":"Replayed Mart '"$RUN"'","reason":null,"actorUserId":null}'
dlt_line() { printf 'eventId:%s,eventType:VendorSubmitted,eventVersion:1,aggregateType:Vendor,occurredAt:%s,kafka_dlt-original-topic:vendor.events,kafka_dlt-original-partition:0,kafka_dlt-original-offset:%s,kafka_dlt-original-consumer-group:notification-service.dispatch,kafka_dlt-exception-fqcn:org.springframework.dao.QueryTimeoutException,kafka_dlt-exception-message:simulated outage\t%s|%s\n' "$GOOD" "$(micros)" "$1" "$VID" "$PAYLOAD"; }
dlt_line 900000001 | produce vendor.events.DLT
poll "a valid event that was dead-lettered shows up for notification-service" 20 "$GOOD" GET "/api/admin/ops/notification/dead-letters?status=PENDING&size=100" "$ATOKEN"
DLG=$(echo "$LAST" | body | grep -o "\"id\":\"vendor.events.DLT:[0-9]*:[0-9]*\"[^}]*$GOOD" | head -1 | cut -d'"' -f4)
has "replay it: REPLAYED" '"status":"REPLAYED"' "$(call POST "/api/admin/ops/notification/dead-letters/$DLG/replay" "$ATOKEN")"
await "its effect happened: admins got the approval notification" admin 10 "Replayed Mart $RUN"
expect "replaying the same dead letter again → 409" 409 "$(call POST "/api/admin/ops/notification/dead-letters/$DLG/replay" "$ATOKEN")"
dlt_line 900000002 | produce vendor.events.DLT    # the same event dead-lettered a second time (same eventId)
poll "a second copy of the same event" 20 "\"originalOffset\":900000002" GET "/api/admin/ops/notification/dead-letters?status=PENDING&size=100" "$ATOKEN"
DLG2=$(echo "$LAST" | body | grep -o "\"id\":\"vendor.events.DLT:[0-9]*:[0-9]*\"[^}]*900000002" | head -1 | cut -d'"' -f4)
has "replaying the copy succeeds" '"status":"REPLAYED"' "$(call POST "/api/admin/ops/notification/dead-letters/$DLG2/replay" "$ATOKEN")"
sleep 2
n=$(call GET "/api/notifications?size=50" "$ATOKEN" | body | grep -o "Replayed Mart $RUN" | wc -l)
[ "$n" = 1 ] && ok "… but the event was applied once (1 notification, processed_event)" || bad "idempotent replay" "$n notifications"

echo "== Setup: approved vendor, priced and stocked product, customer"
call POST /api/admin/vendors/$VID/approve "$ATOKEN" >/dev/null
for _ in $(seq 1 20); do VTOKEN=$(refresh "$VJAR"); claims "$VTOKEN" | grep -q "\"vendor_id\":\"$VID\"" && break; sleep 0.5; done
CAT=$(call POST /api/admin/categories "$ATOKEN" "{\"name\":\"Smoke Hardening $RUN\"}" | body | json id)
RICE=$(call POST /api/vendor/products "$VTOKEN" '{"name":"Hardening Rice","categoryId":"'"$CAT"'","unitValue":1,"unitOfMeasure":"KG","foodType":"VEG"}' | body | json id)
call POST /api/vendor/products/$RICE/publish "$VTOKEN" >/dev/null
for _ in $(seq 1 60); do [ "$(call GET /api/vendor/inventory/$RICE "$VTOKEN" | cut -d' ' -f1)" = 200 ] && [ "$(call GET /api/vendor/prices/$RICE "$VTOKEN" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
call PUT /api/vendor/prices/$RICE "$VTOKEN" '{"mrp":120.00,"sellingPrice":100.00}' >/dev/null
call POST /api/vendor/inventory/$RICE/adjustments "$VTOKEN" '{"type":"SET","quantity":30,"reason":"STOCK_COUNT"}' >/dev/null
poll "product in stock" 20 '"inStock":true' GET /api/catalog/products/$RICE
CTOKEN=$(register "cust10-$RUN@example.com" CUSTOMER "Hardening Shopper")
for _ in $(seq 1 60); do [ "$(call GET /api/customers/me "$CTOKEN" | cut -d' ' -f1)" = 200 ] && break; sleep 0.25; done
ADDR=$(call POST /api/customers/me/addresses "$CTOKEN" '{"label":"Home","recipientName":"Hardening Shopper","phone":"+919876543210","line1":"1 Test Street","city":"Bengaluru","state":"Karnataka","postalCode":"560001","country":"IN","latitude":12.975,"longitude":77.60}' | body | json id)
place() { # key -> orderId
  call DELETE /api/cart "$CTOKEN" >/dev/null
  call POST /api/cart/items "$CTOKEN" '{"type":"PRODUCT","productId":"'"$RICE"'","quantity":1}' >/dev/null
  local t; t=$(call GET "/api/cart/preview?addressId=$ADDR" "$CTOKEN" | body | grep -o '"total":[0-9.]*' | tail -1 | cut -d: -f2)
  call POST /api/orders "$CTOKEN" "{\"addressId\":\"$ADDR\",\"expectedTotal\":$t}" "Idempotency-Key: $(key "$1")" | body | json id
}

echo "== Acceptance: a missed webhook is recovered"
O1=$(place recon); ON1=$(call GET /api/orders/$O1 "$CTOKEN" | body | json orderNumber)
poll "order $ON1 is payable" 20 '^200 ' GET /api/payments/orders/$O1/checkout "$CTOKEN"
r=$(call POST /api/payments/simulator/orders/$O1/pay "$CTOKEN" '{"outcome":"SUCCESS"}'); expect "the customer pays (simulator) — but the verify call and the webhook are both lost" 200 "$r"
has "… so the payment is still PENDING" '"status":"PENDING"' "$(call GET /api/payments/orders/$O1 "$CTOKEN")"
poll "reconciliation finds the capture: payment CAPTURED" 45 '"status":"CAPTURED"' GET /api/payments/orders/$O1 "$CTOKEN"
poll "… and the order is CONFIRMED" 15 '"status":"CONFIRMED"' GET /api/orders/$O1 "$CTOKEN"
has "the reconciliation summary lists it" "$(both '"recovered":[1-9]' "$ON1")" "$(call GET /api/admin/payments/reconciliation "$ATOKEN")"

echo "== Order event timeline"
r=$(call GET /api/admin/orders/$O1/events "$ATOKEN"); expect "the order's event timeline" 200 "$r"
for e in OrderCreated StockReserved OrderAwaitingPayment PaymentInitiated PaymentCaptured OrderConfirmed; do
  echo "$r" | grep -q "\"eventType\":\"$e\"" && ok "timeline has $e" || bad "timeline $e" "$(echo "$r" | cut -c1-300)"
done
has "consumed events are marked APPLIED, published ones PUBLISHED" "$(both '"direction":"CONSUMED"' '"outcome":"APPLIED"').*\"direction\":\"PUBLISHED\"|\"direction\":\"PUBLISHED\".*$(both '"direction":"CONSUMED"' '"outcome":"APPLIED"')" "$r"
pos() { echo "$r" | grep -o '"eventType":"[A-Za-z]*"' | grep -n "\"$1\"" | head -1 | cut -d: -f1; }
[ "$(pos OrderCreated)" -lt "$(pos PaymentCaptured)" ] && [ "$(pos PaymentCaptured)" -lt "$(pos OrderConfirmed)" ] && ok "oldest first" || bad "timeline order" "$(echo "$r" | grep -o '"eventType":"[A-Za-z]*"' | tr '\n' ' ')"
expect "customers cannot read it" 403 "$(call GET /api/admin/orders/$O1/events "$CTOKEN")"

echo "== Acceptance: stuck orders show up on the admin dashboard (and resolve themselves)"
after=$(docker inspect grocery-order-service-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^ORDER_STUCK_CONFIRMED_AFTER=' | cut -d= -f2)
if [ "$after" = 20s ]; then
  ok "order $ON1 is CONFIRMED and its store does not answer (threshold 20 s)"
  poll "the watchdog flags it (STORE_RESPONSE_OVERDUE)" 60 "$(both "$ON1" 'STORE_RESPONSE_OVERDUE')" GET /api/admin/orders/stuck "$ATOKEN"
  has "… with the status, how long it is overdue and its store" "$(both '"status":"CONFIRMED"' '"minutesOverdue":[0-9]')" "$LAST"
  await "admins get a STUCK_ORDER notification" admin 15 "$(both '"type":"STUCK_ORDER"' "$ON1")"
  sleep 12   # two more watchdog runs (5 s apart)
  n=$(call GET "/api/notifications?size=50" "$ATOKEN" | body | grep -o "Order $ON1 is stuck" | wc -l)
  [ "$n" = 1 ] && ok "flagged once, not on every watchdog run (1 notification)" || bad "one flag per stuck order" "$n notifications"
  expect "the store finally accepts" 200 "$(call POST /api/vendor/orders/$O1/accept "$VTOKEN" '{"readyInMinutes":15}')"
  poll "the order moves on (ACCEPTED)" 15 '"status":"ACCEPTED"' GET /api/orders/$O1 "$CTOKEN"
  for _ in $(seq 1 40); do call GET /api/admin/orders/stuck "$ATOKEN" | grep -q "$ON1" || break; sleep 0.5; done
  call GET /api/admin/orders/stuck "$ATOKEN" | grep -q "$ON1" && bad "the flag resolves itself" "still listed" || ok "the stuck flag resolved itself"
  has "the timeline shows the watchdog's OrderStuckDetected and the store's answer" "$(both '"eventType":"OrderStuckDetected"' '"eventType":"VendorOrderAccepted"')" "$(call GET /api/admin/orders/$O1/events "$ATOKEN")"
else
  skip "stuck orders" "ORDER_STUCK_CONFIRMED_AFTER is ${after:-15m}; start with ORDER_STUCK_CONFIRMED_AFTER=20s"
fi

echo "== Housekeeping: old outbox rows are deleted, Debezium does not mind"
retention=$(docker inspect grocery-order-service-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep GROCERY_OUTBOX_CLEANUP_RETENTION | cut -d= -f2)
if [ "$retention" = "2m" ]; then
  for _ in $(seq 1 30); do old=$(sql "SELECT COUNT(*) FROM order_db.outbox_event WHERE occurred_at < UTC_TIMESTAMP(6) - INTERVAL 3 MINUTE"); [ "$old" = 0 ] && break; sleep 5; done
  [ "$old" = 0 ] && ok "order_db: no outbox rows older than the 2 min retention (+1 min)" || bad "outbox cleanup" "$old old rows"
  recent=$(sql "SELECT COUNT(*) FROM order_db.outbox_event"); ok "order_db keeps its $recent recent outbox rows"
  bash "$(dirname "$0")/check-connectors.sh" > "$PAR/conn2" 2>&1 && ok "every connector still RUNNING after the deletes" || bad "connectors after cleanup" "$(cat "$PAR/conn2")"
else
  skip "outbox cleanup" "retention is ${retention:-7d}; start with OUTBOX_RETENTION=2m OUTBOX_CLEANUP_INTERVAL=30s HOUSEKEEPING_INITIAL_DELAY=30s"
fi

echo "== Acceptance: a failed connector task is visible and restarted"
r=$(call GET /api/admin/system/connectors "$ATOKEN"); expect "admins see connector health" 200 "$r"
has "… every connector and task RUNNING" "$(both '"order-outbox"' '"state":"RUNNING"')" "$r"
expect "a customer does not" 403 "$(call GET /api/admin/system/connectors "$CTOKEN")"
expect "a support agent sees connector health" 200 "$(call GET /api/admin/system/connectors "$STOKEN")"
# Break identity-outbox in a way Connect's config validation accepts but the outbox router rejects at runtime: the
# payload column does not exist, so the NEXT identity event fails the task (a wrong password would be refused on PUT).
CFG="$ROOT/services/identity-service/deploy/debezium-connector.json"
sed 's/"transforms.outbox.table.field.event.payload": *"[^"]*"/"transforms.outbox.table.field.event.payload": "payload_missing"/' "$CFG" \
  | python -c "import json,sys; c=json.load(sys.stdin); c.pop('name',None); print(json.dumps(c))" > "$PAR/broken.json"
python -c "import json,sys; c=json.load(open(sys.argv[1])); c.pop('name',None); print(json.dumps(c))" "$CFG" > "$PAR/good.json"
code=$(curl -s -o "$PAR/put" -w '%{http_code}' -X PUT -H 'Content-Type: application/json' --data @"$PAR/broken.json" "$CONNECT/connectors/identity-outbox/config")
[ "$code" = 200 ] && ok "identity-outbox reconfigured with a payload column that does not exist" || bad "break the connector" "$code $(cat "$PAR/put")"
sleep 5   # Connect restarts the task with the new configuration
LATE="late10-$RUN@example.com"; LTOKEN=$(register "$LATE" CUSTOMER "Late Event")
[ -n "$LTOKEN" ] && ok "a user registers while the connector is broken (UserRegistered waits in the binlog)" || bad "register during outage" "no token"
poll "the failed task is visible through the admin API" 60 'identity-outbox.{0,600}"state":"FAILED"' GET /api/admin/system/connectors "$ATOKEN"
has "… with its error trace" 'payload_missing' "$LAST"
expect "… and the event really is held back (no customer profile yet)" 404 "$(call GET /api/customers/me "$LTOKEN")"
for _ in $(seq 1 60); do docker logs grocery-connect-watchdog-1 2>&1 | grep -q "identity-outbox.*restarted the failed tasks" && break; sleep 1; done
docker logs grocery-connect-watchdog-1 2>&1 | grep -q "identity-outbox.*restarted the failed tasks" && ok "the watchdog restarted the failed task on its own" || bad "watchdog restart" "$(docker logs --tail 5 grocery-connect-watchdog-1 2>&1)"
expect "a support agent may not restart connectors" 403 "$(call POST /api/admin/system/connectors/identity-outbox/restart "$STOKEN")"
r=$(call POST /api/admin/system/connectors/identity-outbox/restart "$ATOKEN"); s=${r%% *}
case "$s" in 20[024]) ok "an operator can restart it too ($s)";; *) bad "operator restart" "$r";; esac
code=$(curl -s -o "$PAR/put" -w '%{http_code}' -X PUT -H 'Content-Type: application/json' --data @"$PAR/good.json" "$CONNECT/connectors/identity-outbox/config")
[ "$code" = 200 ] && ok "configuration restored" || bad "restore the connector" "$code $(cat "$PAR/put")"
curl -s -o /dev/null -X POST "$CONNECT/connectors/identity-outbox/restart?includeTasks=true&onlyFailed=true"
for _ in $(seq 1 30); do bash "$(dirname "$0")/check-connectors.sh" > "$PAR/conn3" 2>&1 && break; sleep 2; done
bash "$(dirname "$0")/check-connectors.sh" > "$PAR/conn3" 2>&1 && ok "every connector RUNNING again" || bad "connector restore" "$(cat "$PAR/conn3")"
poll "nothing was lost: the late UserRegistered arrived (customer profile exists)" 30 '^200 ' GET /api/customers/me "$LTOKEN"

echo "== Cleanup: cancel this run's open order (its delivery would compete for other runs' partners)"
r=$(call POST /api/admin/orders/$O1/cancel "$ATOKEN" '{"reason":"Smoke test cleanup"}'); cs=${r%% *}
case "$cs" in 200|409) ok "open order of this run cancelled";; *) bad "cleanup cancel" "$r";; esac

echo "== Internal endpoints stay internal"
expect "/internal/** is not routed by the gateway" 404 "$(call GET /internal/vendors/$VID/store "$CTOKEN")"

rm -f "$VJAR"
echo
echo "Passed: $PASS   Failed: $FAIL   Skipped: $SKIP"
[ "$FAIL" -eq 0 ]
