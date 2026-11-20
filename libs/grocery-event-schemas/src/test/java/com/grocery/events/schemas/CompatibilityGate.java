package com.grocery.events.schemas;

import io.confluent.kafka.schemaregistry.CompatibilityChecker;
import io.confluent.kafka.schemaregistry.CompatibilityLevel;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The build's compatibility gate: Confluent's own {@link CompatibilityChecker} (the code the Schema Registry runs on
 * registration) at {@link #LEVEL}, against the released versions kept in
 * {@code src/test/resources/compatibility-baseline/<topic>/<baseName>/v<k>.avsc}.
 */
final class CompatibilityGate {

    /** The level every subject is pinned to (ADR-0011, register-schemas.sh). */
    static final CompatibilityLevel LEVEL = CompatibilityLevel.BACKWARD_TRANSITIVE;
    static final String BASELINE_ROOT = "compatibility-baseline";

    private static final Pattern VERSION_FILE = Pattern.compile("v([1-9][0-9]*)\\.avsc");

    private CompatibilityGate() {
    }

    /** A released version of a schema. */
    record Baseline(int version, Path path, AvroSchema schema) {
    }

    /**
     * The checker's error messages; empty = {@code candidate} can read data written with every released version.
     *
     * @param releasedOldestFirst the released versions in chronological order (v1 first), as the registry keeps them
     */
    static List<String> check(ParsedSchema candidate, List<? extends ParsedSchema> releasedOldestFirst) {
        return check(LEVEL, candidate, releasedOldestFirst);
    }

    static List<String> check(CompatibilityLevel level, ParsedSchema candidate,
                              List<? extends ParsedSchema> releasedOldestFirst) {
        return CompatibilityChecker.checker(level).isCompatible(candidate, releasedOldestFirst);
    }

    /** The released versions of {@code file}, oldest first; empty when its baseline folder does not exist. */
    static List<Baseline> baselines(EventSchemaFile file) {
        Path dir = baselineRoot().resolve(file.topic()).resolve(file.baseName());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Baseline> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files.sorted().toList()) {
                Matcher matcher = VERSION_FILE.matcher(path.getFileName().toString());
                if (!matcher.matches()) {
                    throw new IllegalStateException("Unexpected file in " + dir + ": " + path.getFileName()
                            + " (baseline files are v1.avsc, v2.avsc, ...)");
                }
                result.add(new Baseline(Integer.parseInt(matcher.group(1)), path,
                        new AvroSchema(Files.readString(path, StandardCharsets.UTF_8))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        result.sort(Comparator.comparingInt(Baseline::version));
        return result;
    }

    static List<AvroSchema> schemas(List<Baseline> baselines) {
        return baselines.stream().map(Baseline::schema).toList();
    }

    static Path baselineRoot() {
        return EventSchemaFile.classpathDirectory(BASELINE_ROOT);
    }
}
