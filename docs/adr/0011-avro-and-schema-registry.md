# ADR-0011: Avro event payloads with a central Schema Registry

- **Status:** Accepted
- **Date:** 2026-11-11 (between Phase 6 and Phase 7)

## Context

Domain events are written to each service's outbox in the business transaction. Debezium relays them to Kafka
(ADR-0002). Until now the payload was schemaless JSON: a producer could rename or drop a field and consumers in
other services would only find out at runtime. With 12 services and about 50 event types, the event contracts need:
- one central, reviewed place for them;
- compatibility checked automatically before a change can ship.

Constraints:
- Topic names, keys, headers, the outbox and Debezium flow, and every consumer's business logic stay unchanged.
- Consumers keep their tolerant, service-owned DTOs (ADR-0007: no shared domain classes).

## Decision

- **Format: Apache Avro on the wire, in the Confluent wire format**: magic byte, 4-byte schema id, Avro binary.
  Keys stay plain-string aggregate ids. Headers (`eventId`, `eventType`, `eventVersion`, correlation ids) are
  unchanged.
- **Central schemas:** the `libs/grocery-event-schemas` module holds one `.avsc` per event at
  `avro/<topic>/<EventType>.avsc`, with record name `com.grocery.events.<domain>.<EventType>`. It is a versioned,
  resource-only jar, with no generated classes. Producing services depend on it at a pinned version, so each
  producer writes exactly the schema it was built and tested with.
  - **Which version to pin (Phase 10 refinement):** there is no artifact repository yet, and every image build
    installs the jar from the current sources. Only the current version can be resolved on a fresh machine or after
    the Maven cache is cleared; an older pin fails with "Could not find artifact". So every service pins the
    **current** version and moves with it. This is safe because a new minor version only adds event types or
    optional fields (enforced by the compatibility gate). Once a repository exists (CodeArtifact, Phase 14), pins may
    differ per service again.
- **Registry: Confluent Schema Registry** (`confluentinc/cp-schema-registry`, 8.2.4 locally):
  - **Subjects:** subject naming is `TopicRecordNameStrategy`, giving `<topic>-<record full name>`, because each
    topic carries several event types.
  - **Compatibility:** `BACKWARD_TRANSITIVE`, as the global default and pinned per subject. A new schema must be
    able to read data written by every earlier version, so any event still on a topic or in a dead-letter topic can
    be replayed.
- **Who registers schemas:** only the pipeline. `infrastructure/local/schema-registry/register-schemas.sh` (the
  `schema-init` container locally, CI later) checks each schema's compatibility against all registered versions,
  then registers it. Services run with `auto.register.schemas=false` and `use.latest.version=false`.
- **Validation happens three times:**
  1. The schema module's build fails if a schema is not `BACKWARD_TRANSITIVE`-compatible with every released
     baseline version. It uses Confluent's own checker.
  2. The registration script refuses an incompatible schema.
  3. The registry rejects it again (HTTP 409).
- **Producers (outbox):**
  - grocery-common's `JdbcOutboxPublisher` converts the payload object to Avro, strictly against its central schema.
    An unknown field, a wrong type or a missing required value fails the business transaction instead of publishing
    a bad event.
  - It encodes the result with Confluent's `KafkaAvroSerializer` and stores it in the new `payload_avro` column next
    to the JSON `payload`, which is kept for auditing, debugging and the existing tests.
  - Debezium's EventRouter publishes `payload_avro` byte for byte with `io.debezium.converters.BinaryDataConverter`,
    keeping JsonConverter as the delegate for anything that is not bytes. This uses
    `"transforms.outbox.table.field.event.payload": "payload_avro"` and `"binary.handling.mode": "bytes"`.
  - Business code still calls `outbox.publish(OutboxEvent.of(...))`.
- **Consumers:**
  - grocery-common installs a value deserializer for every consumer. It decodes Avro with Confluent's
    `KafkaAvroDeserializer`, using the writer's schema from the registry, and renders the record back into the same
    JSON the producer's Jackson wrote.
  - Listeners still receive `ConsumerRecord<String, String>`, so no listener, DTO or test changes.
  - Values that are not Avro (JSON written before the switch) pass through unchanged, so the rollout needs no
    big-bang cut-over.
  - A registry outage is a *transient* failure: the record is retried and never dead-lettered. Undecodable bytes are
    poison and go to `<topic>.DLT` immediately, carrying the original bytes so a replay is byte-identical.
- **Type mapping:**
  - UUID → `string`/`uuid`.
  - Instant → `long`/`timestamp-micros`.
  - LocalDate → `int`/`date`.
  - Money → `bytes`/`decimal(12,2)`.
  - Java enums → `string`, not Avro enums, because adding an Avro enum symbol breaks older readers.
  - A field is nullable only where the code allows it: `["null", T]` with default null.
- **Breaking changes:** a breaking change becomes a new event version:
  - a new `eventVersion` header;
  - a new file `<EventType>.v2.avsc` with record `<EventType>V2`;
  - both versions published side by side until consumers have moved.

  Additive changes (a new optional field with a default) keep the version.

## Rollout order (production and local)

1. Start the registry and register the schemas (`schema-init`).
2. Deploy services on grocery-common 0.6.0 with the `payload_avro` column (Flyway). They write both columns.
3. Switch each Debezium connector to `payload_avro` and the byte-array converter. Consumers accept both formats
   throughout.

**Rollback:** set `grocery.events.avro.enabled=false`, which restores JSON-only publishing and string consumers, and
re-point the connectors to `payload`.

## Consequences

- ✅ Event contracts are explicit, reviewed in one place and checked for compatibility before anything ships; a
  producer can no longer silently break a consumer.
- ✅ Smaller messages, and Kafka UI shows them decoded through the registry.
- ✅ No business, listener or topic changes. JSON remains in the outbox table for auditing.
- ⚠️ The registry is new infrastructure, and a producer needs it the first time it uses each schema. Services warm
  up all schema ids at startup and cache them for their whole lifetime. With the registry stopped, the whole Phase 2
  smoke test still passed locally. Production runs the registry highly available (≥ 2 instances, `_schemas`
  replicated 3×, authentication via `grocery.events.avro.client.*`).
- ⚠️ Decimals travel at their schema scale. A value the producer wrote as `500` reaches consumers as `500.000`:
  numerically equal, but not the same text. catalog-query normalises `unitValue` so that search documents are
  unchanged.
- ⚠️ Each payload is stored twice in the outbox table (JSON and Avro). Outbox rows are short-lived, and cleanup
  arrives in Phase 10.
- ⚠️ Confluent Schema Registry is under the Confluent Community License, which allows this use. A managed registry
  (Confluent Cloud, or Apicurio with its Confluent-compatible API) can replace it without code changes.

## Alternatives considered

- **Debezium converting the JSON column to Avro** (`expand.json.payload` + AvroConverter): rejected. Its schemas are
  inferred from each message, not managed centrally, so nulls and empty arrays change the inferred schema.
- **Generated SpecificRecord classes shared by all services:** rejected. It would couple every consumer to a shared
  artifact (ADR-0007) and require rewriting every listener. Consumers keep tolerant readers.
- **Protobuf / JSON Schema:** both are supported by the same registry. Avro was requested, and it is the most mature
  in the Kafka and Debezium ecosystem.
- **`FULL_TRANSITIVE`:** stricter (forward and backward), but it forbids adding a field that old readers cannot skip.
  Backward compatibility was the requirement, and tolerant readers already absorb forward changes.
