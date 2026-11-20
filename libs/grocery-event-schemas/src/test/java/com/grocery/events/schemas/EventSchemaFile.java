package com.grocery.events.schemas;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One packaged schema file, {@code avro/<topic>/<EventType>.avsc} (or {@code <EventType>.v<n>.avsc} for a breaking
 * version n >= 2), read from the test classpath, i.e. exactly what the jar ships.
 *
 * @param topic     Kafka topic = the folder name, e.g. {@code order.events}
 * @param baseName  file name without {@code .avsc}, e.g. {@code OrderCreated} or {@code OrderCreated.v2}
 * @param eventType the {@code eventType} header value, e.g. {@code OrderCreated}
 * @param version   the event version: 1 without suffix, n for {@code .v<n>}
 * @param path      the file on disk (target/classes)
 */
record EventSchemaFile(String topic, String baseName, String eventType, int version, Path path) {

    /** Classpath folder of the packaged schemas. */
    static final String CLASSPATH_ROOT = "avro";
    /** Namespace prefix: the record full name is {@code com.grocery.events.<domain>.<EventType>}. */
    static final String NAMESPACE_PREFIX = "com.grocery.events.";

    private static final Pattern TOPIC = Pattern.compile("[a-z][a-z0-9-]*\\.[a-z][a-z0-9.-]*");
    private static final Pattern FILE = Pattern.compile("([A-Z][A-Za-z0-9]*)(?:\\.v([2-9]|[1-9][0-9]+))?\\.avsc");

    /** Every packaged schema, sorted by topic and file name. Fails on any file that breaks the layout rules. */
    static List<EventSchemaFile> all() {
        Path root = classpathDirectory(CLASSPATH_ROOT);
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> of(root, file))
                    .sorted(Comparator.comparing(EventSchemaFile::id))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static EventSchemaFile of(Path root, Path file) {
        Path relative = root.relativize(file);
        if (relative.getNameCount() != 2) {
            throw new IllegalStateException("Schema files must be at avro/<topic>/<EventType>.avsc, found avro/"
                    + relative.toString().replace('\\', '/'));
        }
        String topic = relative.getName(0).toString();
        String fileName = relative.getName(1).toString();
        if (!TOPIC.matcher(topic).matches()) {
            throw new IllegalStateException("Folder '" + topic + "' is not a topic name like order.events");
        }
        Matcher name = FILE.matcher(fileName);
        if (!name.matches()) {
            throw new IllegalStateException("File " + topic + "/" + fileName
                    + " must be named <EventType>.avsc or <EventType>.v<n>.avsc (n >= 2), EventType in PascalCase");
        }
        int version = name.group(2) == null ? 1 : Integer.parseInt(name.group(2));
        return new EventSchemaFile(topic, fileName.substring(0, fileName.length() - ".avsc".length()),
                name.group(1), version, file);
    }

    static Path classpathDirectory(String location) {
        URL url = EventSchemaFile.class.getClassLoader().getResource(location);
        if (url == null) {
            throw new IllegalStateException("Classpath folder '" + location + "' not found");
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code <topic>/<baseName>}, e.g. {@code order.events/OrderCreated}. */
    String id() {
        return topic + "/" + baseName;
    }

    /** The topic name before the first dot, e.g. {@code order}. */
    String domain() {
        return topic.substring(0, topic.indexOf('.'));
    }

    String expectedNamespace() {
        return NAMESPACE_PREFIX + domain();
    }

    /** {@code <EventType>} for version 1, {@code <EventType>V<n>} for a breaking version n >= 2. */
    String expectedName() {
        return version == 1 ? eventType : eventType + "V" + version;
    }

    String fullName() {
        return expectedNamespace() + "." + expectedName();
    }

    /** Schema Registry subject under TopicRecordNameStrategy: {@code <topic>-<record full name>}. */
    String subject() {
        return topic + "-" + fullName();
    }

    /** Where producers load it from: {@code avro/<topic>/<file>}. */
    String classpathLocation() {
        return CLASSPATH_ROOT + "/" + topic + "/" + baseName + ".avsc";
    }

    String text() {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parsed exactly as the Schema Registry parses it (no references: every schema is self-contained). */
    AvroSchema parse() {
        return new AvroSchema(text());
    }

    @Override
    public String toString() {
        return id();
    }
}
