package com.grocery.common.deadletter;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.IsolationLevel;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Keeps this instance's {@link DeadLetterIndex} up to date, in its own background thread with its own consumer in
 * {@code assign} mode (no consumer group, nothing committed, every instance reads everything from the beginning):
 * <ul>
 *   <li>every partition of every {@code <topic>.DLT} of this service's listener containers — records whose
 *       {@code kafka_dlt-original-consumer-group} is one of this service's groups are indexed, the rest ignored (a DLT
 *       is shared by all consumer groups of its topic);</li>
 *   <li>{@code _grocery.dead-letter-actions} — the latest state of this service's dead letters.</li>
 * </ul>
 * Never blocks start-up and survives Kafka being down (logged once, then retried with a growing pause up to 30 s);
 * topics that do not exist yet are looked up again every {@code metadata-refresh-interval}. Records removed by the
 * DLT's retention are forgotten.
 *
 * <p>{@link #catchUp} lets a request wait (bounded) until this instance has read everything written so far, so an
 * action taken on one replica is seen by the next request on another one.
 */
public class DeadLetterReader implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReader.class);
    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(10);
    private static final long MAX_BACKOFF_MILLIS = 30_000;

    private final Map<String, Object> consumerConfig;
    private final Map<String, Object> adminConfig;
    private final DeadLetterListeners listeners;
    private final DeadLetterIndex index;
    private final DeadLetterProperties properties;
    private final boolean autoStartup;

    private final Object progress = new Object();
    private final Map<TopicPartition, Long> positions = new ConcurrentHashMap<>();
    private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();
    private volatile Set<TopicPartition> assigned = Set.of();
    private volatile Set<String> consumerGroups = Set.of();
    private volatile boolean running;
    private volatile boolean healthy = true;
    private volatile boolean initialized;
    private volatile KafkaConsumer<byte[], byte[]> consumer;
    private volatile Thread thread;
    private Admin admin;

    /**
     * @param baseConfig the service's consumer configuration (bootstrap servers, security, …); group, offsets,
     *                   deserializers and topic auto-creation are overridden
     */
    public DeadLetterReader(Map<String, Object> baseConfig, DeadLetterListeners listeners, DeadLetterIndex index,
                            DeadLetterProperties properties, boolean autoStartup) {
        this.consumerConfig = readerConfig(baseConfig);
        this.adminConfig = adminConfig(baseConfig, properties.getCatchUpTimeout());
        this.listeners = listeners;
        this.index = index;
        this.properties = properties;
        this.autoStartup = autoStartup;
    }

    static Map<String, Object> readerConfig(Map<String, Object> base) {
        Map<String, Object> config = new HashMap<>(base);
        config.remove(ConsumerConfig.GROUP_ID_CONFIG);
        config.remove(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG);
        config.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
        config.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
        config.keySet().removeIf(key -> key.startsWith("spring.deserializer."));
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, IsolationLevel.READ_UNCOMMITTED.toString());
        Object clientId = config.get(ConsumerConfig.CLIENT_ID_CONFIG);
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, (clientId == null ? "grocery" : clientId) + "-dead-letters");
        return config;
    }

    private static Map<String, Object> adminConfig(Map<String, Object> base, Duration timeout) {
        Set<String> adminNames = AdminClientConfig.configNames();
        Map<String, Object> config = base.entrySet().stream()
                .filter(e -> adminNames.contains(e.getKey()) && e.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Object clientId = config.get(AdminClientConfig.CLIENT_ID_CONFIG);
        config.put(AdminClientConfig.CLIENT_ID_CONFIG, (clientId == null ? "grocery" : clientId) + "-dead-letters-admin");
        int millis = (int) Math.max(1_000, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, millis);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, millis);
        return config;
    }

    // ---- lifecycle ----

    @Override
    public boolean isAutoStartup() {
        return autoStartup;
    }

    /** After the listener containers (their phase is {@code Integer.MAX_VALUE - 100}); stopped before them. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 50;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread reader = new Thread(this::run, "grocery-dead-letters");
        reader.setDaemon(true);
        thread = reader;
        reader.start();
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        KafkaConsumer<byte[], byte[]> current = consumer;
        if (current != null) {
            current.wakeup();
        }
        Thread reader = thread;
        if (reader != null) {
            reader.interrupt();
            try {
                reader.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        synchronized (progress) {
            if (admin != null) {
                admin.close(Duration.ofSeconds(2));
                admin = null;
            }
            progress.notifyAll();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ---- what requests use ----

    /** The partitions of {@code _grocery.dead-letter-actions} this instance reads. */
    public Set<TopicPartition> actionPartitions() {
        return assigned.stream().filter(tp -> tp.topic().equals(properties.getActionsTopic())).collect(Collectors.toSet());
    }

    /** The DLT partitions this instance reads. */
    public Set<TopicPartition> deadLetterPartitions() {
        return assigned.stream().filter(tp -> !tp.topic().equals(properties.getActionsTopic())).collect(Collectors.toSet());
    }

    /**
     * Waits until this instance has read every record written so far to {@code partitions} (and has looked its topics
     * up at least once), at most {@code grocery.dead-letters.catch-up-timeout}. Returns at once when the reader is not
     * running or Kafka is unreachable.
     *
     * @return whether the view is known to be up to date
     */
    public boolean catchUp(Collection<TopicPartition> partitions) {
        long deadline = System.nanoTime() + properties.getCatchUpTimeout().toNanos();
        if (!running || !healthy) {
            return false;
        }
        try {
            synchronized (progress) {
                while (!initialized && running) {
                    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) {
                        return false;
                    }
                    progress.wait(remaining);
                }
            }
            Set<TopicPartition> known = new HashSet<>(partitions);
            known.retainAll(assigned);
            if (known.isEmpty()) {
                return true;
            }
            Map<TopicPartition, Long> ends = endOffsets(known, deadline);
            synchronized (progress) {
                while (!reached(ends)) {
                    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0 || !running) {
                        log.warn("Dead-letter view not caught up within {} (positions {}, end offsets {}): answering "
                                + "with what this instance has read", properties.getCatchUpTimeout(), positions, ends);
                        return false;
                    }
                    progress.wait(remaining);
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Dead-letter view not caught up (end offsets unavailable: {}): answering with what this instance "
                    + "has read", e.toString());
            return false;
        }
    }

    private Map<TopicPartition, Long> endOffsets(Set<TopicPartition> partitions, long deadline) throws Exception {
        Admin client;
        synchronized (progress) {
            if (admin == null) {
                admin = Admin.create(adminConfig);
            }
            client = admin;
        }
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        partitions.forEach(tp -> request.put(tp, OffsetSpec.latest()));
        long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        Map<TopicPartition, Long> ends = new HashMap<>();
        client.listOffsets(request, new ListOffsetsOptions(IsolationLevel.READ_UNCOMMITTED)).all()
                .get(remaining, TimeUnit.MILLISECONDS)
                .forEach((tp, info) -> ends.put(tp, info.offset()));
        return ends;
    }

    private boolean reached(Map<TopicPartition, Long> ends) {
        for (Map.Entry<TopicPartition, Long> end : ends.entrySet()) {
            Long position = positions.get(end.getKey());
            if (position == null || position < end.getValue()) {
                return false;
            }
        }
        return true;
    }

    // ---- the reader thread ----

    private void run() {
        int failures = 0;
        while (running) {
            KafkaConsumer<byte[], byte[]> current = null;
            try {
                current = new KafkaConsumer<>(consumerConfig, new ByteArrayDeserializer(), new ByteArrayDeserializer());
                consumer = current;
                consumerGroups = listeners.consumerGroups();
                Set<String> topics = new HashSet<>(listeners.deadLetterTopics());
                topics.add(properties.getActionsTopic());
                if (failures == 0) {
                    log.info("Reading the dead letters of consumer groups {} from {} and their states from {}",
                            consumerGroups, new java.util.TreeSet<>(listeners.deadLetterTopics()), properties.getActionsTopic());
                }
                long nextRefresh = 0;
                while (running) {
                    if (System.nanoTime() - nextRefresh >= 0) {
                        refresh(current, topics);
                        nextRefresh = System.nanoTime() + properties.getMetadataRefreshInterval().toNanos();
                    }
                    if (assigned.isEmpty()) {
                        Thread.sleep(properties.getPollTimeout().toMillis());
                        continue;
                    }
                    ConsumerRecords<byte[], byte[]> records = current.poll(properties.getPollTimeout());
                    for (ConsumerRecord<byte[], byte[]> record : records) {
                        handle(record);
                    }
                    updatePositions(current);
                    if (!healthy || failures > 0) {
                        log.info("Dead-letter reader reconnected to Kafka");
                    }
                    healthy = true;
                    failures = 0;
                }
            } catch (WakeupException | InterruptException e) {
                if (!running) {
                    break;
                }
            } catch (InterruptedException e) {
                if (!running) {
                    break;
                }
            } catch (Exception e) {
                healthy = false;
                failures++;
                if (failures == 1) {
                    log.warn("Dead-letter reader cannot read from Kafka (retrying in the background): {}", e.toString());
                } else {
                    log.debug("Dead-letter reader still failing ({} attempts): {}", failures, e.toString());
                }
                synchronized (progress) {
                    progress.notifyAll();
                }
                pause(Math.min(MAX_BACKOFF_MILLIS, 1_000L << Math.min(failures, 5)));
            } finally {
                consumer = null;
                if (current != null) {
                    try {
                        current.close(Duration.ofSeconds(1));
                    } catch (RuntimeException e) {
                        log.debug("Closing the dead-letter consumer failed: {}", e.toString());
                    }
                }
                assigned = Set.of();
                positions.clear();
            }
        }
        log.debug("Dead-letter reader stopped");
    }

    /** Assigns every partition of the topics that exist (new ones read from the beginning); forgets expired records. */
    private void refresh(KafkaConsumer<byte[], byte[]> current, Set<String> topics) {
        Set<TopicPartition> partitions = new HashSet<>();
        for (String topic : topics) {
            List<PartitionInfo> infos = current.partitionsFor(topic, METADATA_TIMEOUT);
            if (infos == null || infos.isEmpty()) {
                if (reportedMissing.add(topic)) {
                    log.info("Topic {} does not exist (yet): its dead letters / states are read once it does", topic);
                }
                continue;
            }
            infos.forEach(info -> partitions.add(new TopicPartition(info.topic(), info.partition())));
        }
        Set<TopicPartition> added = new HashSet<>(partitions);
        added.removeAll(assigned);
        if (!added.isEmpty() || !partitions.equals(assigned)) {
            current.assign(partitions);
            if (!added.isEmpty()) {
                current.seekToBeginning(added);
            }
            assigned = Set.copyOf(partitions);
            positions.keySet().retainAll(partitions);
        }
        Set<TopicPartition> deadLetterPartitions = deadLetterPartitions();
        if (!deadLetterPartitions.isEmpty()) {
            current.beginningOffsets(deadLetterPartitions, METADATA_TIMEOUT)
                    .forEach((tp, start) -> index.evictBelow(tp.topic(), tp.partition(), start));
        }
        if (!initialized) {
            synchronized (progress) {
                initialized = true;
                progress.notifyAll();
            }
        }
    }

    private void handle(ConsumerRecord<byte[], byte[]> record) {
        try {
            if (record.topic().equals(properties.getActionsTopic())) {
                if (record.key() == null) {
                    return;
                }
                String key = new String(record.key(), StandardCharsets.UTF_8);
                int separator = key.indexOf('|');
                if (separator <= 0 || !consumerGroups.contains(key.substring(0, separator))) {
                    return;                                           // another service's dead letter
                }
                index.applyAction(key, record.value() == null ? null : DeadLetterAction.fromJson(record.value()),
                        record.offset());
            } else {
                DeadLetter deadLetter = DeadLetter.from(record);
                if (deadLetter != null && consumerGroups.contains(deadLetter.consumerGroup())) {
                    index.add(deadLetter);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Ignoring unreadable record {}-{}@{}: {}", record.topic(), record.partition(), record.offset(),
                    e.toString());
        }
    }

    private void updatePositions(KafkaConsumer<byte[], byte[]> current) {
        for (TopicPartition tp : assigned) {
            try {
                positions.put(tp, current.position(tp, Duration.ofMillis(200)));
            } catch (org.apache.kafka.common.errors.TimeoutException e) {
                // not known yet; next round
            }
        }
        synchronized (progress) {
            progress.notifyAll();
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
