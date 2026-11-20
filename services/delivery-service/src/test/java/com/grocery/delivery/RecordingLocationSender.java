package com.grocery.delivery;

import com.grocery.delivery.dispatch.location.LocationRecordSender;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;

import java.util.ArrayList;
import java.util.List;

/** Replaces the {@code delivery.location} Kafka producer: records what would be sent, or fails like a broker outage. */
public class RecordingLocationSender implements LocationRecordSender {

    private final List<ProducerRecord<String, byte[]>> records = new ArrayList<>();
    private volatile boolean failing;

    @Override
    public synchronized void send(ProducerRecord<String, byte[]> record) {
        if (failing) {
            throw new KafkaException("Simulated broker outage");
        }
        records.add(record);
    }

    public synchronized List<ProducerRecord<String, byte[]>> records() {
        return List.copyOf(records);
    }

    public void failing(boolean failing) {
        this.failing = failing;
    }

    public synchronized void reset() {
        records.clear();
        failing = false;
    }
}
