#!/bin/bash
# Creates the domain topics once (idempotent). Runs in a one-shot "kafka-init" container.
# Auto topic creation is disabled on the broker, so every topic is declared here on purpose.
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:29092}"
PARTITIONS="${TOPIC_PARTITIONS:-3}"
KT=/opt/kafka/bin/kafka-topics.sh

DOMAIN_TOPICS=(
  identity.events customer.events vendor.events restaurant.events
  catalog.events pricing.events inventory.events order.events
  payment.events delivery.events review.events ai.events
)

echo "Waiting for Kafka at ${BOOTSTRAP} ..."
until $KT --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; do sleep 2; done

for topic in "${DOMAIN_TOPICS[@]}"; do
  $KT --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
      --topic "$topic" --partitions "$PARTITIONS" --replication-factor 1 \
      --config retention.ms=604800000            # 7 days locally
  # Dead-letter topic: records a consumer could not process after retries (inspect/replay in Kafka UI)
  $KT --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
      --topic "$topic.DLT" --partitions 1 --replication-factor 1 \
      --config retention.ms=2592000000           # 30 days
done

# High-frequency, best-effort rider location pings: short retention, no outbox.
$KT --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic delivery.location --partitions "$PARTITIONS" --replication-factor 1 \
    --config retention.ms=3600000                # 1 hour

# Phase 10: status of every dead letter (PENDING / REPLAYED / REPLAY_FAILED / DISCARDED), written by the services'
# dead-letter admin API. Compacted: only the latest status per dead letter is kept.
$KT --bootstrap-server "$BOOTSTRAP" --create --if-not-exists     --topic _grocery.dead-letter-actions --partitions 1 --replication-factor 1     --config cleanup.policy=compact --config min.compaction.lag.ms=60000

echo "Topics:"
$KT --bootstrap-server "$BOOTSTRAP" --list
