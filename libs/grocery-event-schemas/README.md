# grocery-event-schemas

The central Avro schemas of every domain event the platform publishes through the transactional outbox
([ADR-0011](../../docs/adr/0011-avro-and-schema-registry.md)). One `.avsc` per (topic, eventType), derived from the
payload records the producing services pass to `OutboxEvent.of(...)`.

- **Resource-only jar** (`com.grocery:grocery-event-schemas:1.8.0`; 1.0.0 = Phases 2–6, 1.1.0 adds the Phase 8 fulfilment events, 1.2.0 the Phase 9 delivery events, 1.3.0 the Phase 10 `OrderStuckDetected`, 1.4.0 the Phase 11 `ai.events`, 1.5.0 the Phase 12 reviews, returns, goods receipts and coupon fields, 1.6.0 password management and `CustomerProfileUpdated`, 1.7.0 the coupon's name and funding on `OrderCreated` and `OrderConfirmed`, 1.8.0 `SecurityEventRecorded` and `PlatformSettingChanged` for the central audit trail): no generated classes and no runtime
  dependencies. Consumers keep their own tolerant DTOs ([ADR-0007](../../docs/adr/0007-grocery-common-library.md)).
- **Producers** depend on a pinned version. grocery-common's outbox converts each payload strictly against its
  schema, so an unknown field, a wrong type or a missing required value fails the business transaction.
- **The registry** gets the same files: `infrastructure/local/schema-registry/register-schemas.sh` (the
  `schema-init` container locally, CI later) reads `src/main/avro` directly.
- **The build** fails when a schema breaks the conventions below or is not `BACKWARD_TRANSITIVE`-compatible with
  every released version.

## Layout and naming

```
src/main/avro/<topic>/<EventType>.avsc              -> jar: avro/<topic>/<EventType>.avsc
src/main/avro/<topic>/<EventType>.v<n>.avsc         -> a breaking version n >= 2 (none exist yet)
src/test/resources/compatibility-baseline/<topic>/<EventType>/v<k>.avsc   -> released versions, k = 1, 2, ...
```

| Item | Rule | Example |
|---|---|---|
| Folder | the Kafka topic | `order.events` |
| File | the `eventType` string the service passes to `OutboxEvent` | `OrderCreated.avsc` |
| Record name | `<EventType>` (`<EventType>V<n>` for `<EventType>.v<n>.avsc`) | `OrderCreated` |
| Namespace | `com.grocery.events.<domain>`, domain = topic before the first dot | `com.grocery.events.order` |
| Full name | namespace + record name | `com.grocery.events.order.OrderCreated` |
| Top-level keys | `"type": "record"`, `"name"`, `"namespace"` first, then `"doc"`, `"fields"` | |
| Nested records | defined inline in the file that uses them (no references to other files), same namespace, named `<EventType><Thing>`; a type used twice in one file is defined once and then referenced by name | `OrderCreatedItem`, `ProductUpsertedIdName` |
| Field names, order | exactly the JSON property names and order the service's Jackson writes (record component names) | `orderNumber` |
| Docs | every record and every field has a `doc`; the top-level doc says `Producer: <service>, topic <topic>, key <id>` | |
| Characters | plain ASCII, no tabs (the registration script only strips line breaks and escapes quotes) | |

Nested record names are unique across the whole module, so even a tool that parses every file with one
`Schema.Parser` cannot hit a "Can't redefine" error. Renaming a nested record later is a breaking change: Avro
compares record names.

## Type mapping

The grocery-common converter implements exactly this mapping from the Java type of a payload component:

| Java type | Avro |
|---|---|
| `String` | `"string"` |
| `UUID` | `{"type":"string","logicalType":"uuid"}` |
| `Instant`, `OffsetDateTime` | `{"type":"long","logicalType":"timestamp-micros"}` (sub-microsecond digits are truncated) |
| `LocalDate` | `{"type":"int","logicalType":"date"}` |
| `LocalTime`, and any value whose exact text must survive | `"string"` (opening hours are already `"HH:mm"` strings) |
| `BigDecimal` | `{"type":"bytes","logicalType":"decimal","precision":P,"scale":S}`, with P and S from the DB column the value comes from. Values are rescaled exactly, never rounded: a value with more decimals than S fails |
| `int`/`Integer`, `long`/`Long`, `double`/`Double`, `float`/`Float`, `boolean`/`Boolean` | `"int"`, `"long"`, `"double"`, `"float"`, `"boolean"` |
| Java enum | `"string"`, not an Avro enum (adding a symbol to an Avro enum breaks older readers); the `doc` lists the values |
| `List<T>` / `Set<T>` | `{"type":"array","items":T}` |
| `Map<String,V>` | `{"type":"map","values":V}` |
| nested record / object | inline named record |
| free-form JSON (`JsonNode`, `Map<String,Object>` with mixed values) | `{"type":"string","logicalType":"json"}` (the JSON text); no event uses it today |
| a value that can be null on any code path | `["null", T]` with `"default": null` (null first) |

A field is required only when the code guarantees a value: primitives, ids, types and timestamps that are always
set, and values a validated request or a NOT NULL column inside the same transaction guarantees. Otherwise the field
is nullable, because a too-strict schema would roll back a business transaction at runtime. Making a required field
nullable later is a compatible change; the reverse is not.

### Decimal precision and scale

| Value | Source column | Avro |
|---|---|---|
| Money: order and payment amounts, unit prices, line totals, MRP, selling prices, refunds | `DECIMAL(12,2)` | `decimal(12,2)` |
| `RestaurantListingUpdated.menu[].items[].price` | `menu_item.price DECIMAL(10,2)` | `decimal(10,2)` |
| `PriceChangeRequested.changePercent` | `price_change_request.change_percent DECIMAL(10,2)` | `decimal(10,2)` |
| `ProductUpserted.unitValue` | `product.unit_value DECIMAL(10,3)` | `decimal(10,3)` |

The service writes `unitValue` with its trailing zeros stripped (`500`). Avro carries it at scale 3 (`500.000`),
which is the same number.

## Subjects and compatibility

- **Subject naming:** `TopicRecordNameStrategy`, which gives `<topic>-<record full name>`, for example
  `order.events-com.grocery.events.order.OrderCreated`. Each event type of a topic has its own subject and history.
- **Compatibility:** `BACKWARD_TRANSITIVE`, as the global default and pinned per subject by the registration
  script. A new version must be able to read data written with **every** earlier version, so any event still on a
  topic or in a dead-letter topic can be replayed.
- **The same check runs three times:**
  1. this module's build: `BaselineCompatibilityTest` with Confluent's own `CompatibilityChecker`, the code the
     registry runs;
  2. the registration script asks the registry before registering;
  3. the registry itself rejects an incompatible schema (HTTP 409).

## Evolving a schema

| Change | Under `BACKWARD_TRANSITIVE` |
|---|---|
| Add a nullable field: `["null", T]` with `"default": null` | ✅ allowed (same event version) |
| Add a required field **with** a default | ✅ allowed |
| Remove a field | ✅ allowed. Consumers that read it must not need it any more |
| Make a required field nullable | ✅ allowed |
| Widen `int` → `long`/`float`/`double`, or `long` → `float`/`double` | ✅ allowed |
| Rename a field **with** `"aliases": ["oldName"]` | ✅ allowed, but the JSON property name changes for consumers: avoid |
| Edit a `doc` | ✅ allowed |
| Add a required field without a default | ❌ breaking |
| Change a field's type (for example `string` → `long`, `string` → enum) | ❌ breaking |
| Rename a field without an alias | ❌ breaking |
| Make a nullable field required | ❌ breaking |
| Rename a record, or a nested record | ❌ breaking |
| Change a decimal's scale, or lower its precision | ❌ breaking in meaning. Avro's checker ignores it, so this module's own test (`decimalsKeepTheirScaleAndNeverLosePrecision`) rejects it |
| A change of meaning with the same shape | ❌ breaking: no checker sees it, so reviewers must |

**A breaking change becomes a new event version.** Add `<EventType>.v2.avsc` with record `<EventType>V2` (subject
`<topic>-com.grocery.events.<domain>.<EventType>V2`). Producers publish it with `eventVersion=2`, side by side with
v1, until every consumer has moved. Then v1 is retired: delete its file, its baseline folder and its manifest
entry. The v2 file gets its own baseline folder `compatibility-baseline/<topic>/<EventType>.v2/`.

## Release steps

1. **Change the schema** in `src/main/avro/<topic>/<EventType>.avsc`, together with the producer's payload record.
   - A **new event** also adds its entry to `SchemaManifestTest.EXPECTED`, a row to the table below and its first
     baseline `compatibility-baseline/<topic>/<EventType>/v1.avsc` (a copy of the new file). The gate requires every
     schema to have at least one baseline version.
2. **Run the tests** (`mvn clean install`, see below). The gate checks the changed schema against every released
   baseline version.
3. **Bump the module version** in `pom.xml`, for example `1.0.0` → `1.1.0` for an additive change, and pin the new
   version in the producing service(s).
4. **Record the release in the baseline:** copy the released schema to
   `compatibility-baseline/<topic>/<EventType>/v<N+1>.avsc`. From now on, every later change must also stay
   readable for this version.
5. **Register** before the producers deploy: `infrastructure/local/schema-registry/register-schemas.sh` (the
   `schema-init` container locally) or its CI equivalent. Services never register schemas themselves
   (`auto.register.schemas=false`).

## Build and test

From the repository root (Git Bash; everything runs in Docker):

```
MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx512m -v "$PWD:/workspace" -v grocery-m2:/root/.m2 -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -B -f libs/grocery-event-schemas/pom.xml clean install
```

Use `clean`: the tests read the schemas from `target/classes`, so a renamed or deleted file would otherwise linger
there. `install` puts the jar into the shared `grocery-m2` volume, where the services resolve it.

| Test class | What it guards |
|---|---|
| `SchemaConventionsTest` | Every file parses (Confluent `AvroSchema` and a fresh plain Avro parser, which proves there are no references to other files). Also checks: name = file name; namespace from the folder; top-level key order; docs everywhere; camelCase field names; unions only as `["null", T]` with `default: null`; valid logical types from the mapping; decimals with explicit precision and scale; no Avro enums; nested-record naming; unique full names across the module; ASCII only |
| `SchemaManifestTest` | The module ships exactly the expected (topic, eventType) list, each on the classpath at `avro/<topic>/<EventType>.avsc`; producer named in the doc; subject naming |
| `BaselineCompatibilityTest` | The gate: each schema is `BACKWARD_TRANSITIVE`-compatible with all its baseline versions. Also checks: baselines are contiguous `v1..vN` with the same full name; no orphan baseline folders; decimal scale and precision guard |
| `CompatibilityGateRulesTest` | The gate rejects breaking edits of a real schema and accepts allowed ones, and it is transitive (BACKWARD alone would miss an old version) |
| `SampleRoundTripTest` | Representative payloads (nested arrays, optional records, decimals, timestamps, nulls) binary-encode and decode with the standard Avro conversions. A missing required value or an over-precise amount is rejected |
| `contract.EventSampleGeneratorTest` | The sample generator gives every schema, in both variants, a valid record. The record survives Confluent's serializer and has realistic values: UUIDs, documented enum values, decimals at their scale and precision, current timestamps, INR. The catalog also reads the schemas from a jar |
| `contract.ConsumerContractTest` | The consumer contract runner: listener discovery, the dispatch-code scan (constants, prefix + suffix pairs), types or topics without schemas, delivery and failure reporting |

## Event consumer contract tests (Phase 13, test-jar)

Every consumer service proves that its listeners accept a valid sample of **every event type on the topics they
subscribe to** ([Phase 13 §4](../../docs/api/phase-13-observability-and-testing.md), "Contract (events)"). The shared
helper lives in this module's test sources, package `com.grocery.events.contract`. It is attached as a **test-jar of
the same version**; the main jar is unchanged and stays resource-only.

```xml
<dependency>
    <groupId>com.grocery</groupId>
    <artifactId>grocery-event-schemas</artifactId>
    <version>1.8.0</version>
    <type>test-jar</type>
    <scope>test</scope>
</dependency>
```

| Class | Role |
|---|---|
| `EventSchemaCatalog` | every packaged schema on the classpath (this module's `target/classes` or the jar a service depends on), with topic, event type, version and documented key |
| `EventSampleGenerator` | a valid record for any schema in the raw Avro representation the platform's serializers use: logical types (uuid, timestamps, date, decimal fitted to precision and scale), unions, nested records, arrays, maps. Values come from the field name and the doc: the first `One of:` value, `e.g.` examples, ids as UUIDs, INR, deadlines in the future. Variants `FULL` (every optional field set) and `MINIMAL` (every optional field null). Avro's `RandomData` is not usable: it fills a decimal with random bytes and a uuid with random text |
| `EventSample` | one generated event: Confluent wire format through `KafkaAvroSerializer` (TopicRecordNameStrategy, a `mock://` registry), the outbox router's headers (`eventId`, `eventType`, `eventVersion`, `occurredAt` in epoch µs, `traceparent`, ...), and the record after the service's own value deserializer |
| `ConsumerContract` | finds the `@KafkaListener` methods and their topics. A bytecode scan of the dispatch code (JDK ClassFile API: `case` labels, `equals`, `Set`/`Map` constants, prefix + suffix pairs) tells handled types from ignored ones. It fails on a subscribed topic without schemas or a dispatched type without schema. It hands each sample to the listener bean twice (delivery and redelivery) and fails on any exception, naming what the error handler would do (DLT at once / retries / outage) |

A consumer service's `EventConsumerContractTest` extends its `AbstractIntegrationTest` (the cached Spring context,
listeners not started) and runs one parameterized case per listener × event type × variant. A consumer that needs
related state seeds it through its own listener with a generated sample of the upstream event (for example
inventory-service: `ProductUpserted` before `GoodsReceived`).

**Releasing:** CodeArtifact versions are immutable. A change to the helper (or to any schema) ships as a new module
version, and the services move their pinned version. Locally, `mvn install` replaces the `1.7.0` artifacts in the
`grocery-m2` volume.

## Events

91 schemas, 13 topics. All are event version 1.

| Topic | eventType | Producer | Schema file |
|---|---|---|---|
| identity.events | UserRegistered | identity-service | `src/main/avro/identity.events/UserRegistered.avsc` |
| identity.events | UserSuspended | identity-service | `src/main/avro/identity.events/UserSuspended.avsc` |
| identity.events | UserReactivated | identity-service | `src/main/avro/identity.events/UserReactivated.avsc` |
| identity.events | PasswordResetRequested | identity-service | `src/main/avro/identity.events/PasswordResetRequested.avsc` |
| identity.events | PasswordChanged | identity-service | `src/main/avro/identity.events/PasswordChanged.avsc` |
| customer.events | CustomerProfileUpdated | customer-service | `src/main/avro/customer.events/CustomerProfileUpdated.avsc` |
| vendor.events | VendorRegistered | vendor-service | `src/main/avro/vendor.events/VendorRegistered.avsc` |
| vendor.events | VendorSubmitted | vendor-service | `src/main/avro/vendor.events/VendorSubmitted.avsc` |
| vendor.events | VendorApproved | vendor-service | `src/main/avro/vendor.events/VendorApproved.avsc` |
| vendor.events | VendorRejected | vendor-service | `src/main/avro/vendor.events/VendorRejected.avsc` |
| vendor.events | VendorSuspended | vendor-service | `src/main/avro/vendor.events/VendorSuspended.avsc` |
| vendor.events | VendorReinstated | vendor-service | `src/main/avro/vendor.events/VendorReinstated.avsc` |
| vendor.events | VendorOrderReceived | vendor-service | `src/main/avro/vendor.events/VendorOrderReceived.avsc` |
| vendor.events | VendorOrderAccepted | vendor-service | `src/main/avro/vendor.events/VendorOrderAccepted.avsc` |
| vendor.events | VendorOrderRejected | vendor-service | `src/main/avro/vendor.events/VendorOrderRejected.avsc` |
| vendor.events | VendorOrderPreparing | vendor-service | `src/main/avro/vendor.events/VendorOrderPreparing.avsc` |
| vendor.events | VendorOrderReadyForPickup | vendor-service | `src/main/avro/vendor.events/VendorOrderReadyForPickup.avsc` |
| restaurant.events | RestaurantRegistered | restaurant-service | `src/main/avro/restaurant.events/RestaurantRegistered.avsc` |
| restaurant.events | RestaurantSubmitted | restaurant-service | `src/main/avro/restaurant.events/RestaurantSubmitted.avsc` |
| restaurant.events | RestaurantApproved | restaurant-service | `src/main/avro/restaurant.events/RestaurantApproved.avsc` |
| restaurant.events | RestaurantRejected | restaurant-service | `src/main/avro/restaurant.events/RestaurantRejected.avsc` |
| restaurant.events | RestaurantSuspended | restaurant-service | `src/main/avro/restaurant.events/RestaurantSuspended.avsc` |
| restaurant.events | RestaurantReinstated | restaurant-service | `src/main/avro/restaurant.events/RestaurantReinstated.avsc` |
| restaurant.events | RestaurantListingUpdated | restaurant-service | `src/main/avro/restaurant.events/RestaurantListingUpdated.avsc` |
| restaurant.events | RestaurantOrderReceived | restaurant-service | `src/main/avro/restaurant.events/RestaurantOrderReceived.avsc` |
| restaurant.events | RestaurantOrderAccepted | restaurant-service | `src/main/avro/restaurant.events/RestaurantOrderAccepted.avsc` |
| restaurant.events | RestaurantOrderRejected | restaurant-service | `src/main/avro/restaurant.events/RestaurantOrderRejected.avsc` |
| restaurant.events | RestaurantOrderPreparing | restaurant-service | `src/main/avro/restaurant.events/RestaurantOrderPreparing.avsc` |
| restaurant.events | RestaurantOrderReadyForPickup | restaurant-service | `src/main/avro/restaurant.events/RestaurantOrderReadyForPickup.avsc` |
| delivery.events | DeliveryPartnerRegistered | delivery-service | `src/main/avro/delivery.events/DeliveryPartnerRegistered.avsc` |
| delivery.events | DeliveryPartnerSubmitted | delivery-service | `src/main/avro/delivery.events/DeliveryPartnerSubmitted.avsc` |
| delivery.events | DeliveryPartnerApproved | delivery-service | `src/main/avro/delivery.events/DeliveryPartnerApproved.avsc` |
| delivery.events | DeliveryPartnerRejected | delivery-service | `src/main/avro/delivery.events/DeliveryPartnerRejected.avsc` |
| delivery.events | DeliveryPartnerSuspended | delivery-service | `src/main/avro/delivery.events/DeliveryPartnerSuspended.avsc` |
| delivery.events | DeliveryPartnerReinstated | delivery-service | `src/main/avro/delivery.events/DeliveryPartnerReinstated.avsc` |
| delivery.events | DeliveryOfferCreated | delivery-service | `src/main/avro/delivery.events/DeliveryOfferCreated.avsc` |
| delivery.events | DeliveryOfferClosed | delivery-service | `src/main/avro/delivery.events/DeliveryOfferClosed.avsc` |
| delivery.events | DeliveryPartnerAssigned | delivery-service | `src/main/avro/delivery.events/DeliveryPartnerAssigned.avsc` |
| delivery.events | DeliveryPartnerUnassigned | delivery-service | `src/main/avro/delivery.events/DeliveryPartnerUnassigned.avsc` |
| delivery.events | DeliveryPickedUp | delivery-service | `src/main/avro/delivery.events/DeliveryPickedUp.avsc` |
| delivery.events | DeliveryCompleted | delivery-service | `src/main/avro/delivery.events/DeliveryCompleted.avsc` |
| delivery.events | DeliveryFailed | delivery-service | `src/main/avro/delivery.events/DeliveryFailed.avsc` |
| delivery.events | DeliveryAssignmentFailed | delivery-service | `src/main/avro/delivery.events/DeliveryAssignmentFailed.avsc` |
| delivery.events | DeliveryCancelled | delivery-service | `src/main/avro/delivery.events/DeliveryCancelled.avsc` |
| delivery.location | DeliveryLocationUpdated | delivery-service | `src/main/avro/delivery.location/DeliveryLocationUpdated.avsc` |
| catalog.events | ProductUpserted | catalog-command-service | `src/main/avro/catalog.events/ProductUpserted.avsc` |
| catalog.events | CategoryChanged | catalog-command-service | `src/main/avro/catalog.events/CategoryChanged.avsc` |
| pricing.events | PriceChanged | pricing-service | `src/main/avro/pricing.events/PriceChanged.avsc` |
| pricing.events | PriceChangeRequested | pricing-service | `src/main/avro/pricing.events/PriceChangeRequested.avsc` |
| pricing.events | PriceChangeApproved | pricing-service | `src/main/avro/pricing.events/PriceChangeApproved.avsc` |
| pricing.events | PriceChangeRejected | pricing-service | `src/main/avro/pricing.events/PriceChangeRejected.avsc` |
| pricing.events | PriceChangeCancelled | pricing-service | `src/main/avro/pricing.events/PriceChangeCancelled.avsc` |
| inventory.events | StockAdjusted | inventory-service | `src/main/avro/inventory.events/StockAdjusted.avsc` |
| inventory.events | ProductAvailabilityChanged | inventory-service | `src/main/avro/inventory.events/ProductAvailabilityChanged.avsc` |
| inventory.events | LowStockDetected | inventory-service | `src/main/avro/inventory.events/LowStockDetected.avsc` |
| inventory.events | StockReserved | inventory-service | `src/main/avro/inventory.events/StockReserved.avsc` |
| inventory.events | StockReservationFailed | inventory-service | `src/main/avro/inventory.events/StockReservationFailed.avsc` |
| inventory.events | StockReservationExpired | inventory-service | `src/main/avro/inventory.events/StockReservationExpired.avsc` |
| inventory.events | StockReleased | inventory-service | `src/main/avro/inventory.events/StockReleased.avsc` |
| inventory.events | StockCommitted | inventory-service | `src/main/avro/inventory.events/StockCommitted.avsc` |
| order.events | OrderCreated | order-service | `src/main/avro/order.events/OrderCreated.avsc` |
| order.events | OrderAwaitingPayment | order-service | `src/main/avro/order.events/OrderAwaitingPayment.avsc` |
| order.events | OrderConfirmed | order-service | `src/main/avro/order.events/OrderConfirmed.avsc` |
| order.events | OrderRejected | order-service | `src/main/avro/order.events/OrderRejected.avsc` |
| order.events | OrderCancelled | order-service | `src/main/avro/order.events/OrderCancelled.avsc` |
| order.events | OrderAccepted | order-service | `src/main/avro/order.events/OrderAccepted.avsc` |
| order.events | OrderPreparing | order-service | `src/main/avro/order.events/OrderPreparing.avsc` |
| order.events | OrderReadyForPickup | order-service | `src/main/avro/order.events/OrderReadyForPickup.avsc` |
| order.events | OrderOutForDelivery | order-service | `src/main/avro/order.events/OrderOutForDelivery.avsc` |
| order.events | OrderDelivered | order-service | `src/main/avro/order.events/OrderDelivered.avsc` |
| order.events | OrderDeliveryFailed | order-service | `src/main/avro/order.events/OrderDeliveryFailed.avsc` |
| order.events | OrderStuckDetected | order-service | `src/main/avro/order.events/OrderStuckDetected.avsc` |
| order.events | ReturnRequested | order-service | `src/main/avro/order.events/ReturnRequested.avsc` |
| order.events | ReturnApproved | order-service | `src/main/avro/order.events/ReturnApproved.avsc` |
| order.events | ReturnRejected | order-service | `src/main/avro/order.events/ReturnRejected.avsc` |
| order.events | ReturnEscalated | order-service | `src/main/avro/order.events/ReturnEscalated.avsc` |
| payment.events | PaymentInitiated | payment-service | `src/main/avro/payment.events/PaymentInitiated.avsc` |
| payment.events | PaymentCaptured | payment-service | `src/main/avro/payment.events/PaymentCaptured.avsc` |
| payment.events | PaymentFailed | payment-service | `src/main/avro/payment.events/PaymentFailed.avsc` |
| payment.events | PaymentExpired | payment-service | `src/main/avro/payment.events/PaymentExpired.avsc` |
| payment.events | RefundCompleted | payment-service | `src/main/avro/payment.events/RefundCompleted.avsc` |
| payment.events | RefundFailed | payment-service | `src/main/avro/payment.events/RefundFailed.avsc` |
| ai.events | ApprovalRequested | ai-orchestrator-service | `src/main/avro/ai.events/ApprovalRequested.avsc` |
| ai.events | ApprovalDecided | ai-orchestrator-service | `src/main/avro/ai.events/ApprovalDecided.avsc` |
| ai.events | ApprovalExecuted | ai-orchestrator-service | `src/main/avro/ai.events/ApprovalExecuted.avsc` |
| ai.events | ApprovalFailed | ai-orchestrator-service | `src/main/avro/ai.events/ApprovalFailed.avsc` |
| review.events | ReviewSubmitted | review-service | `src/main/avro/review.events/ReviewSubmitted.avsc` |
| review.events | ReviewModerated | review-service | `src/main/avro/review.events/ReviewModerated.avsc` |
| review.events | ReviewReplied | review-service | `src/main/avro/review.events/ReviewReplied.avsc` |
| review.events | RatingSummaryUpdated | review-service | `src/main/avro/review.events/RatingSummaryUpdated.avsc` |
| vendor.events | GoodsReceived | vendor-service | `src/main/avro/vendor.events/GoodsReceived.avsc` |

**Shared payload records.** The six lifecycle events of each of vendor.events, restaurant.events and
delivery.events are built from one Java record (`VendorLifecycleEvent`, `RestaurantLifecycleEvent`,
`PartnerLifecycleEvent`). Their schemas have identical fields and differ only in record name and docs. They are
still separate files and subjects, so each can evolve on its own.

**Not here:** customer-service, cart-service and catalog-query-service publish no events. Planned events that are
not implemented yet (vendor/restaurant order handling, deliveries, `delivery.location`, reviews, AI approvals) get
their schema in the change that implements them.

## Changes in 1.1.0 (Phase 8)
- 13 new event types: `VendorOrder*` and `RestaurantOrder*` (Received, Accepted, Rejected, Preparing,
  ReadyForPickup), plus `OrderAccepted`, `OrderPreparing` and `OrderReadyForPickup`. Each has a v1 baseline.
- `OrderConfirmed` gains optional `subtotal` and `note` (default null). This is compatible with v1, and v2 is in the
  baseline.
- `OrderCancelled`: documentation only (new reasons `STORE_REJECTED` and `STORE_NO_RESPONSE`, actor `STORE`,
  previous statuses up to `READY_FOR_PICKUP`). Field types are unchanged, so the registry stores it as version 2.

## Changes in 1.2.0 (Phase 9)
- `delivery.events`: 9 new types. `DeliveryOfferCreated`, `DeliveryOfferClosed`, `DeliveryPartnerAssigned`,
  `DeliveryPartnerUnassigned`, `DeliveryPickedUp`, `DeliveryCompleted`, `DeliveryFailed`, `DeliveryAssignmentFailed`
  and `DeliveryCancelled` are keyed by orderId.
- A new topic, `delivery.location`, with `DeliveryLocationUpdated` (a direct producer, not the outbox).
- `order.events`: `OrderOutForDelivery`, `OrderDelivered` and `OrderDeliveryFailed`. `OrderConfirmed` gains the
  optional `storeLocation` record (default null); this is compatible, and v3 is in the baseline.
- `inventory.events`: `StockCommitted`.

## Changes in 1.3.0 (Phase 10)
- `order.events`: `OrderStuckDetected`, published by order-service's stuck-order watchdog on the first detection
  of an order that stays in one status too long (`status`, `reason`, `since`, `detectedAt`). It has a v1 baseline.
  No existing schema changed.

## Changes in 1.4.0 (Phase 11)
- A new topic, `ai.events` (producer ai-orchestrator-service, key approvalId), with `ApprovalRequested`,
  `ApprovalDecided`, `ApprovalExecuted` and `ApprovalFailed`: the lifecycle of an AI-proposed action that needs a
  human decision. Each has a v1 baseline. No existing schema changed.

## Changes in 1.5.0 (Phase 12)
- New topic `review.events` (producer review-service): `ReviewSubmitted`, `ReviewModerated`, `ReviewReplied` (key
  reviewId) and `RatingSummaryUpdated` (key targetId).
- `order.events`: `ReturnRequested`, `ReturnApproved`, `ReturnRejected` and `ReturnEscalated`.
  - `OrderCreated` and `OrderConfirmed` gain optional `couponCode`, `discount` and `deliveryFeeDiscount` (default
    null; compatible, new baseline versions).
- `payment.events`: `RefundCompleted` and `RefundFailed` gain an optional `returnId`.
- `vendor.events`: `GoodsReceived` (purchasing; inventory adds the stock).
- `ai.events`: documentation only. The new agents and actions are listed, so the registry stores a new version.

## Changes in 1.6.0 (password management, customer profile)
- `identity.events`: `PasswordResetRequested` (userId, email, fullName, token, expiresAt) and `PasswordChanged`
  (userId, email, fullName, method `CHANGE` / `RESET`, changedAt), both keyed by userId. `PasswordResetRequested`
  carries the raw single-use reset token: notification-service builds the emailed link from it. The link works for 30
  minutes and identity_db keeps only the token's hash.
- The first `customer.events` schema: `CustomerProfileUpdated` (customerId = userId, fullName, phone, updatedAt), from
  customer-service. identity and notification consume it.
- Each has a v1 baseline. No existing schema changed. The test fixture for "a topic without schemas" moved from
  `customer.events` to the made-up `loyalty.events`.
- Pinned by the services that use the new schemas: identity-service and customer-service (producers) and
  notification-service (consumer contract tests). The other services still pin 1.5.0, which stays in the local Maven
  cache (`grocery-m2`) and in the artifact repository; a fresh build cache without it needs `1.5.0` installed again
  (ADR-0011, "Which version to pin").

## Changes in 1.8.0 (the central audit trail)

- `identity.events/SecurityEventRecorded`: one per row of identity-service's `auth_audit` (sign-ins and their
  failures, lockouts, sign-outs, password changes and resets, suspensions, new admins), with the acting admin
  (`actorUserId`) where an admin acted on the account. Only reporting-service consumes it.
- `PlatformSettingChanged` on `delivery.events`, `order.events`, `pricing.events`, `restaurant.events`,
  `review.events` and `vendor.events`: one per changed field of an admin's settings save, written with its
  `platform_setting_change` row (old and new stored text, the admin, the time).
- Both are new event types (baselines v1); no existing schema changes. reporting-service builds its audit trail
  (`GET /api/admin/audit`) from them and from the events that already name an actor.

## Changes in 1.7.0 (coupon name and funding)
- `OrderCreated` and `OrderConfirmed` gain optional `couponName` and `couponFundedBy` (`PLATFORM` / `STORE`, the
  coupon's scope), after `deliveryFeeDiscount`. order-service gets them from pricing-service's redeem answer and keeps
  them on the order; reporting-service fills the coupon names and the platform / store split of its reports from
  them. Both are null without a coupon, and in every event of an order placed before 1.7.0. Default null:
  compatible, new baseline versions (`OrderCreated` v3, `OrderConfirmed` v5). No new event types.
- Every service pins 1.7.0 again (ADR-0011, "Which version to pin"): none needs 1.5.0 or 1.6.0 in the Maven cache
  any more. Moving from 1.5.0 also gives the consumer contract tests of identity.events (vendor, restaurant, delivery)
  samples of `PasswordResetRequested` and `PasswordChanged`, which those listeners ignore.
