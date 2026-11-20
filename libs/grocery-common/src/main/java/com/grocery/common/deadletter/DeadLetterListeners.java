package com.grocery.common.deadletter;

import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.TopicPartitionOffset;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * This service's Kafka consumers, read from the {@link KafkaListenerEndpointRegistry}: which consumer groups are its
 * own, which {@code <topic>.DLT} can hold their dead letters, and which listener container handles a record of a
 * given group and topic. Topics in {@code grocery.dead-letters.excluded-topics} (by default {@code delivery.location},
 * best effort and never dead-lettered) are ignored.
 */
public class DeadLetterListeners {

    private final KafkaListenerEndpointRegistry registry;
    private final Set<String> excludedTopics;

    public DeadLetterListeners(KafkaListenerEndpointRegistry registry, Collection<String> excludedTopics) {
        this.registry = registry;
        this.excludedTopics = Set.copyOf(excludedTopics);
    }

    /** The consumer groups of the containers that consume at least one non-excluded topic. */
    public Set<String> consumerGroups() {
        Set<String> groups = new TreeSet<>();
        for (MessageListenerContainer container : containers()) {
            if (container.getGroupId() != null && !deadLetterTopics(container).isEmpty()) {
                groups.add(container.getGroupId());
            }
        }
        return groups;
    }

    /** {@code <topic>.DLT} of every non-excluded topic a container of this service consumes. */
    public Set<String> deadLetterTopics() {
        Set<String> topics = new TreeSet<>();
        for (MessageListenerContainer container : containers()) {
            if (container.getGroupId() != null) {
                topics.addAll(deadLetterTopics(container));
            }
        }
        return topics;
    }

    /** The container of {@code consumerGroup} that consumes {@code topic}, or {@code null}. */
    public MessageListenerContainer container(String consumerGroup, String topic) {
        for (MessageListenerContainer container : containers()) {
            if (consumerGroup.equals(container.getGroupId()) && consumes(container, topic)) {
                return container;
            }
        }
        return null;
    }

    private Collection<MessageListenerContainer> containers() {
        return registry.getAllListenerContainers();
    }

    private Set<String> deadLetterTopics(MessageListenerContainer container) {
        Set<String> topics = new LinkedHashSet<>();
        for (String topic : topics(container.getContainerProperties())) {
            if (!excludedTopics.contains(topic) && !topic.endsWith(".DLT")) {
                topics.add(topic + ".DLT");
            }
        }
        return topics;
    }

    private static Set<String> topics(ContainerProperties properties) {
        Set<String> topics = new LinkedHashSet<>();
        if (properties.getTopics() != null) {
            topics.addAll(java.util.List.of(properties.getTopics()));
        }
        if (properties.getTopicPartitions() != null) {
            for (TopicPartitionOffset partition : properties.getTopicPartitions()) {
                topics.add(partition.getTopic());
            }
        }
        return topics;
    }

    private static boolean consumes(MessageListenerContainer container, String topic) {
        ContainerProperties properties = container.getContainerProperties();
        if (topics(properties).contains(topic)) {
            return true;
        }
        return properties.getTopicPattern() != null && properties.getTopicPattern().matcher(topic).matches();
    }
}
