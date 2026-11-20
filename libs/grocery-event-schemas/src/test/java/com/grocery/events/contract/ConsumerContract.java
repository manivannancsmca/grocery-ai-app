package com.grocery.events.contract;

import com.grocery.events.contract.EventSampleGenerator.Variant;
import com.grocery.events.contract.EventSchemaCatalog.EventSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Deserializer;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The event consumer contract of one service (docs/api/phase-13-observability-and-testing.md §4, "Contract (events)"):
 * every {@code @KafkaListener} of the service accepts a valid sample of EVERY event type that can arrive on the topics
 * it subscribes to, generated from grocery-event-schemas, in two variants (all optional fields set / all null), and a
 * redelivery of it. "Accepts" = the Avro value deserializes to the producer's JSON, the listener maps it to its own
 * DTO and either applies it or ends in a deliberate outcome (ignored type, duplicate, state-guarded no-op) WITHOUT an
 * exception: any exception would retry the record or dead-letter it, which a valid event must never cause.
 *
 * <p>Everything is derived, nothing is listed by hand:
 * <ul>
 *   <li>the subscribed topics from the {@code @KafkaListener} annotations (read by name, so this helper does not need
 *       spring-kafka);</li>
 *   <li>the samples from the schemas of those topics ({@link EventSchemaCatalog}), so a new event type or field is
 *       covered by the next build;</li>
 *   <li>which event types the listener actually handles (the rest is ignored by design) from the string constants
 *       of its compiled dispatch code ({@link DispatchCode}): a type named in a {@code switch}, an {@code equals},
 *       a {@code Set}/{@code Map} of types, or as a prefix + suffix pair.</li>
 * </ul>
 * The checks fail when a listener subscribes to a topic without any schema, or when its dispatch code names an
 * event type (PascalCase, at least two words) that no schema defines: a handler without a sample would otherwise be
 * untested.
 *
 * <p>Usage in a consumer service (a parameterized test over {@link #cases()}, see the services' EventConsumerContractTest):
 * <pre>{@code
 * static final ConsumerContract CONTRACT = ConsumerContract.builder()
 *         .listener(OrderEventsListener.class)
 *         .listener(DispatchListener.class).dispatchPackages("com.grocery.notification.dispatch")
 *         .build();
 * static Stream<ConsumerContract.Case> cases() { return CONTRACT.cases(); }
 * @ParameterizedTest(name = "{0}") @MethodSource("cases")
 * void acceptsAValidSample(ConsumerContract.Case c) {
 *     CONTRACT.deliver(c, context.getBean(c.listenerClass()), registry, deserializer);
 * }
 * }</pre>
 */
public final class ConsumerContract {

    static final String KAFKA_LISTENER = "org.springframework.kafka.annotation.KafkaListener";
    static final String KAFKA_LISTENERS = "org.springframework.kafka.annotation.KafkaListeners";
    /** What an event type looks like: PascalCase, at least two words ("OrderCreated", not "Order" or "GROCERY"). */
    static final Pattern EVENT_TYPE_SHAPE = Pattern.compile("[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+");

    /** One listener method and one sample to hand it. */
    public record Case(Class<?> listenerClass, Method method, String topic, EventSchema schema, Variant variant,
                       boolean handled) {

        /** {@code <Listener>.<method> <- <topic>/<EventType> v<n> [handled|ignored, full|minimal]}. */
        @Override
        public String toString() {
            return listenerClass.getSimpleName() + "." + method.getName() + " <- " + schema.topic() + "/"
                    + schema.eventType() + " v" + schema.version() + " [" + (handled ? "handled" : "ignored")
                    + ", " + variant.label() + "]";
        }
    }

    /** A listener class with the code its dispatch decision lives in. */
    record ListenerSpec(Class<?> listenerClass, List<Class<?>> dispatchClasses, List<String> dispatchPackages) {
    }

    /** What the scan found for one listener class. */
    public record ListenerReport(Class<?> listenerClass, Map<String, Set<String>> handled,
                                 Map<String, Set<String>> ignored) {
    }

    private final EventSchemaCatalog catalog;
    private final List<ListenerSpec> listeners;
    private final Map<String, Object> values;
    private final Map<String, String> notEventTypes;
    private final List<Case> cases = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final List<ListenerReport> reports = new ArrayList<>();

    private ConsumerContract(EventSchemaCatalog catalog, List<ListenerSpec> listeners, Map<String, Object> values,
                             Map<String, String> notEventTypes) {
        this.catalog = catalog;
        this.listeners = List.copyOf(listeners);
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.notEventTypes = Map.copyOf(notEventTypes);
        for (ListenerSpec listener : this.listeners) {
            analyse(listener);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private EventSchemaCatalog catalog;
        private final List<Class<?>> listenerClasses = new ArrayList<>();
        private final Map<Class<?>, List<Class<?>>> dispatchClasses = new LinkedHashMap<>();
        private final Map<Class<?>, List<String>> dispatchPackages = new LinkedHashMap<>();
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final Map<String, String> notEventTypes = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * A listener bean class (its {@code @KafkaListener} methods are found by reflection) and the other classes
         * its dispatch decision lives in (the listener class itself and its nested classes are always scanned).
         */
        public Builder listener(Class<?> listenerClass, Class<?>... dispatchCode) {
            listenerClasses.add(listenerClass);
            dispatchClasses.computeIfAbsent(listenerClass, c -> new ArrayList<>()).addAll(Arrays.asList(dispatchCode));
            dispatchPackages.computeIfAbsent(listenerClass, c -> new ArrayList<>());
            return this;
        }

        /** Whole packages of dispatch code of the LAST listener added (e.g. a package of event handler beans). */
        public Builder dispatchPackages(String... packageNames) {
            if (listenerClasses.isEmpty()) {
                throw new IllegalStateException("Add the listener first");
            }
            dispatchPackages.get(listenerClasses.getLast()).addAll(Arrays.asList(packageNames));
            return this;
        }

        /**
         * A value for every field of this name in every sample (Java types: String, UUID, Instant, BigDecimal, ...),
         * e.g. {@code orderType=RESTAURANT} so a restaurant consumer's sample is for its own store type.
         */
        public Builder value(String fieldName, Object value) {
            values.put(fieldName, value);
            return this;
        }

        /** A constant of the dispatch code that looks like an event type but is not one (with the reason). */
        public Builder notEventType(String constant, String reason) {
            notEventTypes.put(constant, reason);
            return this;
        }

        /** Another catalog than the classpath's (this module's own tests). */
        Builder catalog(EventSchemaCatalog catalog) {
            this.catalog = catalog;
            return this;
        }

        public ConsumerContract build() {
            List<ListenerSpec> specs = listenerClasses.stream()
                    .map(c -> new ListenerSpec(c, List.copyOf(dispatchClasses.get(c)), List.copyOf(dispatchPackages.get(c))))
                    .toList();
            return new ConsumerContract(catalog != null ? catalog : EventSchemaCatalog.fromClasspath(), specs, values,
                    notEventTypes);
        }
    }

    // ------------------------------------------------------------------------------------------------ analysis

    private void analyse(ListenerSpec listener) {
        Map<Method, List<String>> methods = listenerMethods(listener.listenerClass());
        if (methods.isEmpty()) {
            problems.add(listener.listenerClass().getName() + " has no @KafkaListener method");
            return;
        }
        List<Class<?>> scanned = new ArrayList<>();
        scanned.add(listener.listenerClass());
        scanned.addAll(listener.dispatchClasses());
        Set<String> constants = DispatchCode.stringConstants(scanned, listener.dispatchPackages(),
                listener.listenerClass());

        Set<String> subscribed = new LinkedHashSet<>();
        methods.values().forEach(subscribed::addAll);
        Map<String, Set<String>> handled = new TreeMap<>();
        Map<String, Set<String>> ignored = new TreeMap<>();
        for (Map.Entry<Method, List<String>> method : methods.entrySet()) {
            for (String topic : method.getValue()) {
                List<EventSchema> schemas = catalog.ofTopic(topic);
                if (schemas.isEmpty()) {
                    problems.add(where(listener, method.getKey()) + " subscribes to topic '" + topic
                            + "', which has no schema in grocery-event-schemas: no sample can be generated for any of"
                            + " its event types (add the schemas, or fix the topic name)");
                    continue;
                }
                for (EventSchema schema : schemas) {
                    boolean isHandled = isHandled(schema.eventType(), constants);
                    (isHandled ? handled : ignored).computeIfAbsent(topic, t -> new TreeSet<>())
                            .add(schema.eventType() + (schema.version() == 1 ? "" : " v" + schema.version()));
                    for (Variant variant : Variant.values()) {
                        cases.add(new Case(listener.listenerClass(), method.getKey(), topic, schema, variant,
                                isHandled));
                    }
                }
            }
        }
        reports.add(new ListenerReport(listener.listenerClass(), handled, ignored));

        Set<String> known = catalog.eventTypes();
        Set<String> subscribedTypes = new TreeSet<>();
        subscribed.forEach(topic -> subscribedTypes.addAll(catalog.eventTypes(topic)));
        for (String constant : constants) {
            if (!EVENT_TYPE_SHAPE.matcher(constant).matches() || known.contains(constant)
                    || notEventTypes.containsKey(constant)) {
                continue;
            }
            boolean piece = subscribedTypes.stream()
                    .anyMatch(type -> type.startsWith(constant) || type.endsWith(constant));
            if (!piece) {
                problems.add(listener.listenerClass().getName() + " dispatch code names '" + constant + "', which"
                        + " looks like an event type but no schema in grocery-event-schemas defines it, so no sample can"
                        + " be generated: add its schema (topic " + subscribed + "), or declare it with"
                        + " notEventType(\"" + constant + "\", reason) if it is not an event type");
            }
        }
    }

    /** A type is handled when its name, or a prefix + suffix split of it, is a constant of the dispatch code. */
    static boolean isHandled(String eventType, Set<String> constants) {
        if (constants.contains(eventType)) {
            return true;
        }
        for (int i = 1; i < eventType.length(); i++) {
            if (constants.contains(eventType.substring(0, i)) && constants.contains(eventType.substring(i))) {
                return true;
            }
        }
        return false;
    }

    private static String where(ListenerSpec listener, Method method) {
        return listener.listenerClass().getSimpleName() + "." + method.getName();
    }

    /** The {@code @KafkaListener} methods of the class (or its superclasses: a CGLIB proxy) with their topics. */
    static Map<Method, List<String>> listenerMethods(Class<?> type) {
        Map<Method, List<String>> methods = new LinkedHashMap<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            Method[] declared = current.getDeclaredMethods();
            Arrays.sort(declared, (a, b) -> a.getName().compareTo(b.getName()));
            for (Method method : declared) {
                List<String> topics = new ArrayList<>();
                boolean listener = false;
                for (Annotation annotation : method.getAnnotations()) {
                    String name = annotation.annotationType().getName();
                    if (KAFKA_LISTENER.equals(name)) {
                        listener = true;
                        topics.addAll(topics(annotation, method));
                    } else if (KAFKA_LISTENERS.equals(name)) {
                        listener = true;
                        for (Annotation inner : (Annotation[]) attribute(annotation, "value")) {
                            topics.addAll(topics(inner, method));
                        }
                    }
                }
                if (!listener || methods.keySet().stream().anyMatch(m -> m.getName().equals(method.getName()))) {
                    continue;
                }
                if (method.getParameterCount() != 1
                        || !ConsumerRecord.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    throw new IllegalStateException(method + ": the contract test hands the listener one"
                            + " ConsumerRecord<String, String>; adapt ConsumerContract for other signatures");
                }
                method.setAccessible(true);
                methods.put(method, List.copyOf(new LinkedHashSet<>(topics)));
            }
        }
        return methods;
    }

    private static List<String> topics(Annotation annotation, Method method) {
        String pattern = (String) attribute(annotation, "topicPattern");
        Object partitions = attribute(annotation, "topicPartitions");
        if (!pattern.isEmpty() || (partitions instanceof Object[] array && array.length > 0)) {
            throw new IllegalStateException(method + ": topicPattern / topicPartitions are not supported by the"
                    + " contract test; list the topics");
        }
        List<String> topics = new ArrayList<>();
        for (String topic : (String[]) attribute(annotation, "topics")) {
            if (topic.contains("${") || topic.contains("#{")) {
                throw new IllegalStateException(method + ": topic '" + topic + "' is an expression; the contract"
                        + " test needs literal topic names");
            }
            topics.add(topic);
        }
        return topics;
    }

    private static Object attribute(Annotation annotation, String name) {
        try {
            return annotation.annotationType().getMethod(name).invoke(annotation);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read " + name + " of " + annotation, e);
        }
    }

    // ------------------------------------------------------------------------------------------------ results

    /** One case per listener method x subscribed topic x event type (and version) x variant. */
    public Stream<Case> cases() {
        return cases.stream();
    }

    /** Problems found by the analysis (topics without schemas, dispatched types without schemas). */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Fails with every problem of the analysis. */
    public void verify() {
        if (!problems.isEmpty()) {
            throw new AssertionError(problems.size() + " event consumer contract problem(s):\n - "
                    + String.join("\n - ", problems));
        }
    }

    /** Handled / ignored event types per listener and topic (for reports). */
    public List<ListenerReport> reports() {
        return List.copyOf(reports);
    }

    /** A printable summary: per listener and topic, the handled and the (deliberately) ignored event types. */
    public String summary() {
        StringBuilder out = new StringBuilder("Event consumer contract: ").append(cases.size()).append(" cases (")
                .append(cases.stream().filter(Case::handled).count()).append(" handled)\n");
        for (ListenerReport report : reports) {
            out.append(report.listenerClass().getSimpleName()).append('\n');
            Set<String> topics = new TreeSet<>(report.handled().keySet());
            topics.addAll(report.ignored().keySet());
            for (String topic : topics) {
                Set<String> handled = report.handled().getOrDefault(topic, Set.of());
                Set<String> ignored = report.ignored().getOrDefault(topic, Set.of());
                out.append("  ").append(topic).append(": handled ").append(handled.size()).append(' ')
                        .append(handled).append("; ignored ").append(ignored.size()).append(' ').append(ignored)
                        .append('\n');
            }
        }
        problems.forEach(problem -> out.append("PROBLEM: ").append(problem).append('\n'));
        return out.toString();
    }

    /** The field values every sample gets. */
    public Map<String, Object> values() {
        return values;
    }

    /** A fresh sample for the case (new ids, current timestamps). */
    public EventSample sample(Case c) {
        return EventSample.of(c.schema(), c.variant(), values);
    }

    // ------------------------------------------------------------------------------------------------ delivery

    /** {@link #deliver(Case, Object, SchemaRegistryClient, Deserializer, Consumer)} without preparation. */
    public EventSample deliver(Case c, Object listenerBean, SchemaRegistryClient registry,
                               Deserializer<String> valueDeserializer) {
        return deliver(c, listenerBean, registry, valueDeserializer, sample -> {
        });
    }

    /**
     * Generates the case's sample, lets {@code prepare} seed related state for it (e.g. a projection row keyed by the
     * sample's {@code orderId}), serializes it in the wire format, runs it through the service's value deserializer,
     * and hands the record to the listener bean twice (delivery and redelivery). Fails if anything throws: a valid event
     * must be applied or deliberately skipped, never retried or dead-lettered.
     *
     * @return the delivered sample (for further assertions)
     */
    public EventSample deliver(Case c, Object listenerBean, SchemaRegistryClient registry,
                               Deserializer<String> valueDeserializer, Consumer<EventSample> prepare) {
        EventSample sample = sample(c);
        prepare.accept(sample);
        ConsumerRecord<String, String> record;
        try {
            record = sample.consumerRecord(registry, valueDeserializer);
        } catch (AssertionError e) {
            throw e;
        } catch (RuntimeException e) {
            throw failure(c, sample, "deserializing", e);
        }
        if (!c.method().getDeclaringClass().isInstance(listenerBean)) {
            throw new IllegalArgumentException(listenerBean + " is not a " + c.method().getDeclaringClass().getName());
        }
        for (String delivery : List.of("delivery", "redelivery")) {
            try {
                c.method().invoke(listenerBean, record);
            } catch (InvocationTargetException e) {
                throw failure(c, sample, delivery, e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return sample;
    }

    private static AssertionError failure(Case c, EventSample sample, String step, Throwable cause) {
        return new AssertionError(c + ": a valid " + sample.id() + " failed on " + step + " with "
                + classify(cause) + ": " + cause + "\nPayload: " + sample.describe(), cause);
    }

    /** What the platform's error handler would do with the failure (grocery-common's classification). */
    static String classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            String name = cause.getClass().getName();
            String simpleName = cause.getClass().getSimpleName();
            if (simpleName.equals("InvalidEventException")) {
                return "an InvalidEventException (poison: dead-lettered at once, never retried)";
            }
            if (simpleName.equals("AvroConversionException")) {
                return "an Avro conversion error (poison: dead-lettered at once)";
            }
            if (name.startsWith("tools.jackson.") || name.startsWith("com.fasterxml.jackson.")) {
                return "a JSON mapping error (" + simpleName + ")";
            }
            if (simpleName.equals("TransientEventProcessingException")) {
                return "a TransientEventProcessingException (retried as an outage for 30 min, then dead-lettered)";
            }
        }
        return "a processing error (retried 4 times, then dead-lettered)";
    }
}
