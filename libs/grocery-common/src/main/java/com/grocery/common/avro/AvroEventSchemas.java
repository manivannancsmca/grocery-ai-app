package com.grocery.common.avro;

import org.apache.avro.Schema;
import org.apache.avro.SchemaParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The central event schemas on the classpath (the {@code grocery-event-schemas} jar of producing services):
 * {@code avro/<topic>/<EventType>.avsc} for eventVersion 1 and {@code avro/<topic>/<EventType>.v<n>.avsc} for
 * eventVersion n &gt;= 2. Parsed once and cached.
 */
public class AvroEventSchemas {

    /** Root folder of the schemas on the classpath. */
    public static final String ROOT = "avro";

    private static final Logger log = LoggerFactory.getLogger(AvroEventSchemas.class);
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern FILE = Pattern.compile("([A-Za-z0-9_-]+)(?:\\.v([0-9]+))?\\.avsc");

    private final ClassLoader classLoader;
    private final ConcurrentMap<String, Schema> cache = new ConcurrentHashMap<>();

    public AvroEventSchemas() {
        this(ClassUtils.getDefaultClassLoader());
    }

    public AvroEventSchemas(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /** {@code avro/<topic>/<EventType>.avsc} (v1) or {@code avro/<topic>/<EventType>.v<n>.avsc}. */
    public static String location(String topic, String eventType, int eventVersion) {
        return ROOT + "/" + topic + "/" + eventType + (eventVersion == 1 ? "" : ".v" + eventVersion) + ".avsc";
    }

    /**
     * The schema of one event version.
     *
     * @throws IllegalStateException if the classpath has no such schema (an event is never published unvalidated)
     */
    public Schema require(String topic, String eventType, int eventVersion) {
        if (topic == null || !NAME.matcher(topic).matches() || eventType == null
                || !NAME.matcher(eventType).matches() || eventVersion < 1) {
            throw new IllegalArgumentException("Invalid event coordinates: topic '" + topic + "', eventType '"
                    + eventType + "', eventVersion " + eventVersion);
        }
        String location = location(topic, eventType, eventVersion);
        Schema cached = cache.get(location);
        if (cached != null) {
            return cached;
        }
        URL url = classLoader.getResource(location);
        if (url == null) {
            throw new IllegalStateException("No Avro schema for " + topic + " " + eventType + " v" + eventVersion
                    + " on the classpath (expected " + location + "): add it to libs/grocery-event-schemas and use"
                    + " that version, or switch Avro off with grocery.events.avro.enabled=false");
        }
        Schema schema;
        try (InputStream in = url.openStream()) {
            schema = parse(in, location);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + location, e);
        }
        Schema previous = cache.putIfAbsent(location, schema);
        return previous != null ? previous : schema;
    }

    /**
     * Every schema found under {@code classpath*:avro/<topic>/*.avsc} (first occurrence of a location wins, as for
     * {@link #require}). Unreadable files are logged and skipped.
     */
    public List<EventSchema> all() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver(classLoader)
                    .getResources("classpath*:" + ROOT + "/**/*.avsc");
        } catch (IOException e) {
            log.warn("Cannot list the Avro event schemas on the classpath: {}", e.toString());
            return List.of();
        }
        Map<String, EventSchema> found = new LinkedHashMap<>();
        for (Resource resource : resources) {
            EventSchema schema = toEventSchema(resource);
            if (schema != null) {
                found.putIfAbsent(schema.location(), schema);
            }
        }
        return new ArrayList<>(found.values());
    }

    private EventSchema toEventSchema(Resource resource) {
        String url;
        try {
            url = resource.getURL().toString();
        } catch (IOException e) {
            log.warn("Skipping Avro schema {}: {}", resource, e.toString());
            return null;
        }
        String[] segments = url.split("/");
        if (segments.length < 3 || !ROOT.equals(segments[segments.length - 3])) {
            log.debug("Skipping {}: not avro/<topic>/<EventType>.avsc", url);
            return null;
        }
        String topic = segments[segments.length - 2];
        Matcher file = FILE.matcher(segments[segments.length - 1]);
        if (!NAME.matcher(topic).matches() || !file.matches()) {
            log.warn("Skipping {}: not avro/<topic>/<EventType>.avsc or <EventType>.v<n>.avsc", url);
            return null;
        }
        String eventType = file.group(1);
        int version = file.group(2) == null ? 1 : Integer.parseInt(file.group(2));
        String location = location(topic, eventType, version);
        try (InputStream in = resource.getInputStream()) {
            Schema parsed = parse(in, location);
            Schema schema = cache.computeIfAbsent(location, key -> parsed);
            return new EventSchema(topic, eventType, version, location, schema);
        } catch (IOException | RuntimeException e) {
            log.warn("Skipping unreadable Avro schema {}: {}", url, e.getMessage());
            return null;
        }
    }

    private static Schema parse(InputStream in, String location) throws IOException {
        Schema schema;
        try {
            schema = new Schema.Parser().parse(in);
        } catch (SchemaParseException e) {
            throw new IllegalStateException("Invalid Avro schema " + location + ": " + e.getMessage(), e);
        }
        if (schema.getType() != Schema.Type.RECORD) {
            throw new IllegalStateException("Avro schema " + location + " must be a record, not " + schema.getType());
        }
        return schema;
    }

    /** One event schema found on the classpath. */
    public record EventSchema(String topic, String eventType, int eventVersion, String location, Schema schema) {

        /** {@code <topic>-<record full name>} (TopicRecordNameStrategy). */
        public String subject() {
            return SchemaRegistrySupport.subject(topic, schema.getFullName());
        }
    }
}
