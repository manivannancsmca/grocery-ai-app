package com.grocery.events.contract;

import org.apache.avro.Schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Every event schema packaged by grocery-event-schemas, read from the classpath at {@code avro/<topic>/<EventType>.avsc}
 * (or {@code <EventType>.v<n>.avsc} for a breaking version n &gt;= 2), from the module's own {@code target/classes} or
 * from the jar a consumer service depends on. The catalog is what the contract tests generate their samples from, so a
 * new or changed schema is picked up without touching any test.
 */
public final class EventSchemaCatalog {

    /** Classpath folder of the packaged schemas. */
    public static final String CLASSPATH_ROOT = "avro";

    private static final Pattern TOPIC = Pattern.compile("[a-z][a-z0-9-]*\\.[a-z][a-z0-9.-]*");
    private static final Pattern FILE = Pattern.compile("([A-Z][A-Za-z0-9]*)(?:\\.v([2-9]|[1-9][0-9]+))?\\.avsc");
    /** The top-level doc names the message key: "Producer: order-service, topic order.events, key orderId." */
    private static final Pattern KEY = Pattern.compile("\\bkey ([a-z][A-Za-z0-9]*)");

    private static volatile EventSchemaCatalog classpathCatalog;

    /**
     * One packaged schema.
     *
     * @param topic     Kafka topic (the folder), e.g. {@code order.events}
     * @param eventType the {@code eventType} header, e.g. {@code OrderCreated}
     * @param version   the {@code eventVersion} header: 1 for {@code <EventType>.avsc}, n for {@code .v<n>.avsc}
     * @param schema    the parsed schema (one parser per file: every schema is self-contained)
     * @param keyField  the payload field that is the message key (from the top-level doc), or null if not documented
     */
    public record EventSchema(String topic, String eventType, int version, Schema schema, String keyField) {

        /** {@code <topic>/<EventType>} (plus {@code .v<n>} for n &gt;= 2), e.g. {@code order.events/OrderCreated}. */
        public String id() {
            return topic + "/" + eventType + (version == 1 ? "" : ".v" + version);
        }

        /** The outbox aggregate type a producer would write; consumers do not use it, so the topic's domain is enough. */
        public String aggregateType() {
            String domain = topic.substring(0, topic.indexOf('.'));
            return Character.toUpperCase(domain.charAt(0)) + domain.substring(1);
        }

        @Override
        public String toString() {
            return id();
        }
    }

    private final List<EventSchema> schemas;
    private final Map<String, List<EventSchema>> byTopic;

    private EventSchemaCatalog(List<EventSchema> schemas) {
        this.schemas = List.copyOf(schemas);
        Map<String, List<EventSchema>> topics = new LinkedHashMap<>();
        for (EventSchema schema : this.schemas) {
            topics.computeIfAbsent(schema.topic(), topic -> new ArrayList<>()).add(schema);
        }
        topics.replaceAll((topic, list) -> List.copyOf(list));
        this.byTopic = Collections.unmodifiableMap(topics);
    }

    /** The schemas on the test classpath (cached: the jar does not change during a test run). */
    public static EventSchemaCatalog fromClasspath() {
        EventSchemaCatalog catalog = classpathCatalog;
        if (catalog == null) {
            synchronized (EventSchemaCatalog.class) {
                catalog = classpathCatalog;
                if (catalog == null) {
                    catalog = load(Thread.currentThread().getContextClassLoader() != null
                            ? Thread.currentThread().getContextClassLoader()
                            : EventSchemaCatalog.class.getClassLoader());
                    classpathCatalog = catalog;
                }
            }
        }
        return catalog;
    }

    /** Reads every {@code avro/<topic>/<file>.avsc} the class loader sees (directories and jars). */
    public static EventSchemaCatalog load(ClassLoader classLoader) {
        Map<String, EventSchema> found = new LinkedHashMap<>();
        try {
            Enumeration<URL> roots = classLoader.getResources(CLASSPATH_ROOT);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                withRoot(root, path -> {
                    try (Stream<Path> files = Files.walk(path, 2)) {
                        files.filter(Files::isRegularFile).forEach(file -> {
                            EventSchema schema = parse(path, file);
                            if (schema != null) {
                                found.putIfAbsent(schema.id(), schema);
                            }
                        });
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("No event schema found on the classpath at " + CLASSPATH_ROOT
                    + "/<topic>/<EventType>.avsc: add com.grocery:grocery-event-schemas (test scope is enough)");
        }
        List<EventSchema> sorted = new ArrayList<>(found.values());
        sorted.sort(Comparator.comparing(EventSchema::topic).thenComparing(EventSchema::eventType)
                .thenComparingInt(EventSchema::version));
        return new EventSchemaCatalog(sorted);
    }

    private interface PathAction {
        void accept(Path path) throws IOException;
    }

    private static void withRoot(URL root, PathAction action) throws IOException {
        URI uri;
        try {
            uri = root.toURI();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        if ("file".equals(uri.getScheme())) {
            action.accept(Path.of(uri));
            return;
        }
        if (!"jar".equals(uri.getScheme())) {
            return;                                        // nothing else is used on a Maven test classpath
        }
        FileSystem fileSystem;
        boolean created = false;
        try {
            fileSystem = FileSystems.newFileSystem(uri, Map.of());
            created = true;
        } catch (FileSystemAlreadyExistsException e) {
            fileSystem = FileSystems.getFileSystem(uri);
        }
        try {
            action.accept(fileSystem.provider().getPath(uri));
        } finally {
            if (created) {
                fileSystem.close();
            }
        }
    }

    /** The schema of {@code avro/<topic>/<file>}, or null for a file that is not an event schema. */
    private static EventSchema parse(Path root, Path file) {
        Path relative = root.relativize(file);
        if (relative.getNameCount() != 2) {
            return null;
        }
        String topic = relative.getName(0).toString();
        Matcher name = FILE.matcher(relative.getName(1).toString());
        if (!TOPIC.matcher(topic).matches() || !name.matches()) {
            return null;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Schema schema = new Schema.Parser().parse(text);
        String doc = schema.getDoc() == null ? "" : schema.getDoc();
        Matcher key = KEY.matcher(doc);
        String keyField = key.find() && schema.getField(key.group(1)) != null ? key.group(1) : null;
        int version = name.group(2) == null ? 1 : Integer.parseInt(name.group(2));
        return new EventSchema(topic, name.group(1), version, schema, keyField);
    }

    public List<EventSchema> all() {
        return schemas;
    }

    public Set<String> topics() {
        return byTopic.keySet();
    }

    /** Every schema of the topic (all event types, all versions); empty for an unknown topic. */
    public List<EventSchema> ofTopic(String topic) {
        return byTopic.getOrDefault(topic, List.of());
    }

    /** Every event type of every topic. */
    public Set<String> eventTypes() {
        Set<String> types = new TreeSet<>();
        schemas.forEach(schema -> types.add(schema.eventType()));
        return types;
    }

    /** The event types of one topic. */
    public Set<String> eventTypes(String topic) {
        Set<String> types = new TreeSet<>();
        ofTopic(topic).forEach(schema -> types.add(schema.eventType()));
        return types;
    }

    public Optional<EventSchema> find(String topic, String eventType, int version) {
        return ofTopic(topic).stream()
                .filter(schema -> schema.eventType().equals(eventType) && schema.version() == version)
                .findFirst();
    }

    public EventSchema get(String topic, String eventType) {
        return find(topic, eventType, 1).orElseThrow(() -> new IllegalArgumentException(
                "No schema " + topic + "/" + eventType + " in grocery-event-schemas"));
    }
}
